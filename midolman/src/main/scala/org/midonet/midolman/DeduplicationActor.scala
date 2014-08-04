/*
 * Copyright (c) 2013 Midokura SARL, All Rights Reserved.
 */

package org.midonet.midolman

import java.util.UUID
import java.util.{HashMap => JHashMap, List => JList}
import scala.collection.mutable
import scala.compat.Platform
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

import org.apache.commons.lang.exception.ExceptionUtils
import akka.actor._
import akka.event.{LoggingAdapter, LoggingReceive}
import com.yammer.metrics.core.Clock

import org.midonet.cluster.DataClient
import org.midonet.midolman.HostRequestProxy.FlowStateBatch
import org.midonet.midolman.io.DatapathConnectionPool
import org.midonet.midolman.logging.ActorLogWithoutPath
import org.midonet.midolman.monitoring.metrics.PacketPipelineMetrics
import org.midonet.midolman.rules.Condition
import org.midonet.midolman.simulation.PacketContext
import org.midonet.midolman.state.NatState.{NatKey, NatBinding}
import org.midonet.midolman.state.ConnTrackState.{ConnTrackKey, ConnTrackValue}
import org.midonet.midolman.state.{FlowStateStorage, FlowStatePackets, FlowStateReplicator}
import org.midonet.midolman.topology.rcu.TraceConditions
import org.midonet.netlink.exceptions.NetlinkException
import org.midonet.odp.flows.FlowAction
import org.midonet.odp.{Datapath, FlowMatch, Packet}
import org.midonet.packets.Ethernet
import org.midonet.sdn.flows.WildcardMatch
import org.midonet.util.concurrent.ExecutionContextOps
import org.midonet.sdn.state.{FlowStateLifecycle, FlowStateTransaction}
import org.midonet.sdn.state.FlowStateTable.Reducer
import org.midonet.midolman.FlowController.InvalidateFlowsByTag

object DeduplicationActor {
    // Messages
    case class HandlePackets(packet: Array[Packet])

    case class DiscardPacket(cookie: Int)

    /* This message is sent by simulations that result in packets being
     * generated that in turn need to be simulated before they can correctly
     * be forwarded. */
    case class EmitGeneratedPacket(egressPort: UUID, eth: Ethernet,
                                   parentCookie: Option[Int] = None)

    case class RestartWorkflow(pktCtx: PacketContext)

    // This class holds a cache of actions we use to apply the result of a
    // simulation to pending packets while that result isn't written into
    // the WildcardFlowTable. After updating the table, the FlowController
    // will place the FlowMatch in the pending ring buffer so the DDA can
    // evict the entry from the cache.
    sealed class ActionsCache(var size: Int = 1024,
                              log: LoggingAdapter) {
        size = findNextPowerOfTwo(size)
        private val mask = size - 1
        val actions = new JHashMap[FlowMatch, JList[FlowAction]]()
        val pending = new Array[FlowMatch](size)
        var free = 0L
        var expecting = 0L

        def clearProcessedFlowMatches(): Int = {
            var cleared = 0
            while ((free - expecting) > 0) {
                val idx = index(expecting)
                val flowMatch = pending(idx)
                if (flowMatch == null)
                    return cleared

                actions.remove(flowMatch)
                pending(idx) = null
                expecting += 1
                cleared += 1
            }
            cleared
        }

        def getSlot(cookieStr: String): Int = {
            val res = free
            if (res - expecting == size) {
                log.debug("{} Waiting for the FlowController to catch up", cookieStr)
                var retries = 200
                while (clearProcessedFlowMatches() == 0) {
                    if (retries > 100) {
                        retries -= 1
                    } else if (retries > 0) {
                        retries -= 1
                        Thread.`yield`()
                    } else {
                        Thread.sleep(0)
                    }
                }
                log.debug("{} The FlowController has caught up", cookieStr)
            }
            free += 1
            index(res)
        }

        private def index(x: Long): Int = (x & mask).asInstanceOf[Int]

        private def findNextPowerOfTwo(value: Int) =
            1 << (32 - Integer.numberOfLeadingZeros(value - 1))
    }
}

class CookieGenerator(val start: Int, val increment: Int) {
    private var nextCookie = start

    def next: Int = {
        val ret = nextCookie
        nextCookie += increment
        ret
    }
}

class DeduplicationActor(
            val cookieGen: CookieGenerator,
            val dpConnPool: DatapathConnectionPool,
            val clusterDataClient: DataClient,
            val connTrackStateTable: FlowStateLifecycle[ConnTrackKey, ConnTrackValue],
            val natStateTable: FlowStateLifecycle[NatKey, NatBinding],
            val storage: FlowStateStorage,
            val metrics: PacketPipelineMetrics,
            val packetOut: Int => Unit)
            extends Actor with ActorLogWithoutPath {

    import DatapathController.DatapathReady
    import DeduplicationActor._
    import PacketWorkflow._

    def datapathConn(packet: Packet) = dpConnPool.get(packet.getMatch.hashCode)

    var traceConditions: Seq[Condition] = null

    var datapath: Datapath = null
    var dpState: DatapathState = null

    implicit val dispatcher = this.context.system.dispatcher
    implicit val system = this.context.system

    // data structures to handle the duplicate packets.
    protected val cookieToDpMatch = mutable.HashMap[Integer, FlowMatch]()
    protected val dpMatchToCookie = mutable.HashMap[FlowMatch, Integer]()
    protected val cookieToPendedPackets: mutable.MultiMap[Integer, Packet] =
                                new mutable.HashMap[Integer, mutable.Set[Packet]]
                                with mutable.MultiMap[Integer, Packet]

    protected val simulationExpireMillis = 5000L

    private val waitingRoom = new WaitingRoom[PacketContext](
                                        (simulationExpireMillis millis).toNanos)

    protected val actionsCache = new ActionsCache(log = log)

    protected val connTrackTx = new FlowStateTransaction(connTrackStateTable)
    protected val natTx = new FlowStateTransaction(natStateTable)
    protected var replicator: FlowStateReplicator = _

    protected var workflow: PacketHandler = _

    private var pendingFlowStateBatches = List[FlowStateBatch]()

    private val invalidateExpiredConnTrackKeys =
        new Reducer[ConnTrackKey, ConnTrackValue, Unit]() {
            override def apply(u: Unit, k: ConnTrackKey, v: ConnTrackValue) {
                FlowController ! InvalidateFlowsByTag(k)
            }
        }

    private val invalidateExpiredNatKeys =
        new Reducer[NatKey, NatBinding, Unit]() {
            override def apply(u: Unit, k: NatKey, v: NatBinding) {
                FlowController ! InvalidateFlowsByTag(k)
            }
        }

    override def receive = LoggingReceive {

        case DatapathReady(dp, state) if null == datapath =>
            datapath = dp
            dpState = state
            replicator = new FlowStateReplicator(connTrackStateTable,
                natStateTable,
                storage,
                dpState,
                FlowController ! _,
                datapath)
            pendingFlowStateBatches foreach (self ! _)
            workflow = new PacketWorkflow(dpState, datapath, clusterDataClient,
                                          dpConnPool, actionsCache, replicator)

        case m: FlowStateBatch =>
            if (replicator ne null)
                replicator.importFromStorage(m)
            else
                pendingFlowStateBatches ::= m

        case HandlePackets(packets) =>
            actionsCache.clearProcessedFlowMatches()

            var i = 0
            while (i < packets.length && packets(i) != null) {
                handlePacket(packets(i))
                i += 1
            }

            connTrackStateTable.expireIdleEntries(
                FlowStateStorage.FLOW_STATE_TTL_SECONDS * 1000,
                (), invalidateExpiredConnTrackKeys)
            natStateTable.expireIdleEntries(
                FlowStateStorage.FLOW_STATE_TTL_SECONDS * 1000,
                (), invalidateExpiredNatKeys)

        case RestartWorkflow(pktCtx) =>
            if (pktCtx.idle) {
                metrics.packetsOnHold.dec()
                log.debug("Restarting workflow for {}", pktCtx.cookieStr)
                runWorkflow(pktCtx)
            } else {
                log.error("Tried to restart a non-idle PacketContext: {}", pktCtx)
                drop(pktCtx)
            }

        // This creates a new PacketWorkflow and
        // executes the simulation method directly.
        case EmitGeneratedPacket(egressPort, ethernet, parentCookie) =>
            startWorkflow(Packet.fromEthernet(ethernet), Right(egressPort))

        case TraceConditions(newTraceConditions) =>
            log.debug("traceConditions updated to {}", newTraceConditions)
            traceConditions = newTraceConditions
    }

    // We return collection.Set so we can return an empty immutable set
    // and a non-empty mutable set.
    private def removePendingPacket(cookie: Int): collection.Set[Packet] = {
        val pending = cookieToPendedPackets.remove(cookie)
        log.debug("Remove {} pending packet(s) for cookie {}",
                  if (pending.isDefined) pending.get.size else 0,
                  cookie)
        if (pending.isDefined) {
            val dpMatch = cookieToDpMatch.remove(cookie)
            if (dpMatch.isDefined)
                dpMatchToCookie.remove(dpMatch.get)
            pending.get
        } else {
            Set.empty
        }
    }

    protected def packetContext(packet: Packet,
                                cookieOrEgressPort: Either[Int, UUID],
                                parentCookie: Option[Int] = None)
    : PacketContext = {
        log.debug("Creating new PacketContext for {}", cookieOrEgressPort)

        if (cookieOrEgressPort.isRight)
            packet.generateFlowKeysFromPayload()
        val wcMatch = WildcardMatch.fromFlowMatch(packet.getMatch)

        val expiry = Platform.currentTime + simulationExpireMillis
        val pktCtx = new PacketContext(cookieOrEgressPort, packet, expiry,
                                       parentCookie, wcMatch)
        pktCtx.state.initialize(connTrackTx, natTx)

        def matchTraceConditions(): Boolean = {
            val anyConditionMatching =
                traceConditions exists { _.matches(pktCtx, wcMatch, false) }
            log.debug("Checking packet {} against tracing conditions {}: {}",
                      pktCtx, traceConditions, anyConditionMatching)
            anyConditionMatching
        }

        if (traceConditions ne null)
            pktCtx.setTraced(matchTraceConditions())
        pktCtx
    }

    /**
     * Deal with an incomplete workflow that could not complete because it found
     * a NotYet on the way.
     */
    private def postponeOn(pktCtx: PacketContext, f: Future[_]) {
        log.debug("Packet with {} postponed", pktCtx.cookieStr)
        pktCtx.postpone()
        f.onComplete {
            case Success(_) =>
                log.debug("Issuing restart for simulation {}", pktCtx.cookieStr)
                self ! RestartWorkflow(pktCtx)
            case Failure(ex) =>
                log.info("Failure on waiting suspended packet's future, {}\n{}",
                         ex, ExceptionUtils.getFullStackTrace(ex) )
        }(ExecutionContext.callingThread)
        metrics.packetPostponed()
        giveUpWorkflows(waitingRoom enter pktCtx)
    }

    private def giveUpWorkflows(pktCtxs: IndexedSeq[PacketContext]) {
        var i = 0
        while (i < pktCtxs.size) {
            val pktCtx = pktCtxs(i)
            if (!pktCtx.isStateMessage) {
                if (pktCtx.idle)
                    drop(pktCtx)
                else
                    log.warning("Pending {} was scheduled for cleanup " +
                                "but was not idle", pktCtx)
            }
            i += 1
        }
    }

    private def drop(pktCtx: PacketContext): Unit =
        try {
            workflow.drop(pktCtx)
        } catch {
            case e: Exception =>
                log.error(e, "Failed to drop flow for {}", pktCtx.packet)
        } finally {
            var dropped = 0
            if (pktCtx.ingressed) {
                val cookie = pktCtx.cookieOrEgressPort.left.get
                dropped = removePendingPacket(cookie).size
            }
            metrics.packetsDropped.mark(dropped + 1)
        }

    /**
     * Deal with a completed workflow
     */
    private def complete(pktCtx: PacketContext, path: PipelinePath): Unit = {
        log.debug("Packet with {} processed", pktCtx.cookieStr)
        if (pktCtx.runs > 1)
            waitingRoom leave pktCtx
        pktCtx.cookieOrEgressPort match {
            case Left(cookie) =>
                applyFlow(cookie, pktCtx)
                val latency = (Clock.defaultClock().tick() -
                               pktCtx.packet.startTimeNanos).toInt
                metrics.packetsProcessed.mark()
                path match {
                    case WildcardTableHit =>
                        metrics.wildcardTableHit(latency)
                    case PacketToPortSet =>
                        metrics.packetToPortSet(latency)
                    case Simulation =>
                        metrics.packetSimulated(latency)
                    case _ =>
                }
            case _ => // do nothing
        }
    }

    private def applyFlow(cookie: Int, pktCtx: PacketContext): Unit = {
        val actions = actionsCache.actions.get(pktCtx.packet.getMatch)
        val pendingPackets = removePendingPacket(cookie)
        val numPendingPackets = pendingPackets.size
        if (numPendingPackets > 0) {
            // Send all pended packets with the same action list (unless
            // the action list is empty, which is equivalent to dropping)
            if (actions.isEmpty) {
                metrics.packetsProcessed.mark(numPendingPackets)
            } else {
                log.debug("Sending pended packets {} for cookie {}",
                          pendingPackets, cookie)
                pendingPackets foreach (executePacket(_, actions))
                metrics.pendedPackets.dec(numPendingPackets)
            }
        }

        if (!pktCtx.isStateMessage && actions.isEmpty) {
            system.eventStream.publish(DiscardPacket(cookie))
        }
    }

    /**
     * Handles an error in a workflow execution.
     */
    private def handleErrorOn(pktCtx: PacketContext, ex: Exception): Unit = {
        log.warning("Exception while processing packet {} - {}, {}",
                    pktCtx.cookieStr, ex.getMessage, ex.getStackTraceString)
        drop(pktCtx)
    }

    protected def startWorkflow(packet: Packet,
                                cookieOrEgressPort: Either[Int, UUID],
                                parentCookie: Option[Int] = None): Unit =
        try {
            runWorkflow(packetContext(packet, cookieOrEgressPort, parentCookie))
        } catch {
            case ex: Exception =>
                log.error(ex, "Unable to execute workflow for {}", packet)
        } finally {
            if (cookieOrEgressPort.isLeft)
                packetOut(1)
        }

    protected def runWorkflow(pktCtx: PacketContext): Unit =
        try {
            workflow.start(pktCtx) match {
                case Ready(path) => complete(pktCtx, path)
                case NotYet(f) => postponeOn(pktCtx, f)
            }
        } catch {
            case NotYetException(f, _) => postponeOn(pktCtx, f)
            case ex: Exception => handleErrorOn(pktCtx, ex)
        } finally {
            flushTransactions()
        }

    private def handlePacket(packet: Packet): Unit = {
        val flowMatch = packet.getMatch
        log.debug("Handling packet with match {}", flowMatch)
        val actions = actionsCache.actions.get(flowMatch)
        if (actions != null) {
            log.debug("Got actions from the cache: {}", actions)
            executePacket(packet, actions)
            packetOut(1)
        } else if (FlowStatePackets.isStateMessage(packet)) {
            processPacket(packet)
        } else dpMatchToCookie.get(flowMatch) match {
            case None => processPacket(packet)
            case Some(cookie) => makePending(packet, cookie)
        }
    }

    // No simulation is in progress for the flow match. Create a new
    // cookie and start the packet workflow.
    private def processPacket(packet: Packet): Unit = {
        val newCookie = cookieGen.next
        val flowMatch = packet.getMatch
        log.debug("new cookie #{} for new match {}",
            newCookie, flowMatch)
        dpMatchToCookie.put(flowMatch, newCookie)
        cookieToDpMatch.put(newCookie, flowMatch)
        cookieToPendedPackets.put(newCookie, mutable.Set.empty)
        startWorkflow(packet, Left(newCookie))
    }

    // There is a simulation in progress, so wait until it finishes and
    // apply the resulting actions.
    private def makePending(packet: Packet, cookie: Integer): Unit = {
        log.debug("A matching packet with cookie {} is already " +
                 "being handled", cookie)
        cookieToPendedPackets.addBinding(cookie, packet)
        packetOut(1)
        giveUpWorkflows(waitingRoom.doExpirations())
    }

    private def executePacket(packet: Packet, actions: JList[FlowAction]) {
        if (actions.isEmpty) {
            return
        }

        try {
            datapathConn(packet).packetsExecute(datapath, packet, actions)
        } catch {
            case e: NetlinkException =>
                log.info("Failed to execute packet: {}", e)
        }
        metrics.packetsProcessed.mark()
    }

    private def flushTransactions(): Unit = {
        connTrackTx.flush()
        natTx.flush()
    }
}
