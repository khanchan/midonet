/*
 * Copyright (c) 2012 Midokura SARL, All Rights Reserved.
 */
package org.midonet.netlink;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SelectionKey;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.google.common.base.Function;
import com.sun.jna.Native;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.netlink.clib.cLibrary;
import org.midonet.netlink.exceptions.NetlinkException;
import org.midonet.netlink.messages.Builder;
import org.midonet.util.BatchCollector;
import org.midonet.util.TokenBucket;
import org.midonet.util.io.SelectorInputQueue;

/**
 * Abstract class to be derived by any netlink protocol implementation.
 */
public abstract class AbstractNetlinkConnection {

    private static final Logger log = LoggerFactory
        .getLogger(AbstractNetlinkConnection.class);

    private static final int DEFAULT_MAX_BATCH_IO_OPS = 200;
    private static final int NETLINK_HEADER_LEN = 20;
    private static final int NETLINK_READ_BUFSIZE = 0x10000;

    private AtomicInteger sequenceGenerator = new AtomicInteger(1);

    private Map<Integer, NetlinkRequest>
        pendingRequests = new ConcurrentHashMap<>();

    // Mostly for testing purposes, this flag tells the netlink connection that
    // writes should be done on the channel directly, instead of waiting
    // for a selector to invoke handleWriteEvent()
    private boolean bypassSendQueue = false;

    // Again, mostly for testing purposes. Tweaks the number of IO operations
    // per handle[Write|Read]Event() invokation. Netlink protocol tests will
    // assume one read per call.
    private int maxBatchIoOps = DEFAULT_MAX_BATCH_IO_OPS;

    private ByteBuffer reply = ByteBuffer.allocateDirect(NETLINK_READ_BUFSIZE);

    private BufferPool requestPool;

    private NetlinkChannel channel;
    protected BatchCollector<Runnable> dispatcher;

    private SelectorInputQueue<NetlinkRequest> writeQueue =
            new SelectorInputQueue<NetlinkRequest>();

    private PriorityQueue<NetlinkRequest> expirationQueue;

    private Set<NetlinkRequest> ongoingTransaction = new HashSet<>();

    /* When true, this connection will interpret that the notification
     * handler is shared with other channels and thus an upper entity
     * is responsible for marking the ending notification batches. */
    private boolean usingSharedNotificationHandler = false;

    public AbstractNetlinkConnection(NetlinkChannel channel, BufferPool sendPool) {
        this.channel = channel;
        this.requestPool = sendPool;
        expirationQueue =
            new PriorityQueue<NetlinkRequest>(512, requestComparator);

        // default implementation runs callbacks out of the calling thread one by one
        this.dispatcher = new BatchCollector<Runnable>() {
            @Override
            public void submit(Runnable r) {
                r.run();
            }

            @Override
            public void endBatch() {
            }
        };
    }

    public void setUsingSharedNotificationHandler(boolean v) {
        usingSharedNotificationHandler = true;
    }

    public boolean isUsingSharedNotificationHandler() {
        return usingSharedNotificationHandler;
    }

    public NetlinkChannel getChannel() {
        return channel;
    }

    public void setCallbackDispatcher(BatchCollector<Runnable> dispatcher) {
        this.dispatcher = dispatcher;
    }

    public void bypassSendQueue(final boolean bypass) {
        this.bypassSendQueue = bypass;
    }

    public void setMaxBatchIoOps(int max) {
        this.maxBatchIoOps = max;
    }

    public int getMaxBatchIoOps() {
        return this.maxBatchIoOps;
    }

    public SelectorInputQueue<NetlinkRequest> getSendQueue() {
        return writeQueue;
    }

    public static Function<List<ByteBuffer>, Boolean> alwaysTrueTranslator =
        new Function<List<ByteBuffer>, Boolean>() {
            @Override
            public Boolean apply(List<ByteBuffer> input) {
                return Boolean.TRUE;
            }
        };

    private void expireOldRequests() {
        long now = System.nanoTime();
        NetlinkRequest req;

        while ((req = expirationQueue.peek()) != null &&
                req.expirationTimeNanos <= now) {

            expirationQueue.poll();
            if (pendingRequests.remove(req.seq) == null) {
                // request expired but it has already been
                // handled on the reading side -> ignoring.
                continue;
            }

            log.debug("[pid:{}] Signalling timeout to the callback: {}",
                      pid(), req.seq);
            requestPool.release(req.releaseRequestPayload());
            dispatcher.submit(req.expired());
        }
    }

    protected <T> void sendNetlinkMessage(NetlinkRequestContext ctx,
                                          int messageFlags,
                                          ByteBuffer payload,
                                          Callback<T> callback,
                                          Function<List<ByteBuffer>, T> translator,
                                          final long timeoutMillis) {
        final short flags = (short) messageFlags;
        final short cmdFamily = ctx.commandFamily();
        final byte cmd = ctx.command();
        final byte version = ctx.version();

        final int seq = nextSequenceNumber();

        final int totalSize = payload.limit();

        final ByteBuffer headerBuf = payload.duplicate();
        headerBuf.rewind();
        headerBuf.order(ByteOrder.nativeOrder());
        serializeNetlinkHeader(headerBuf, seq, cmdFamily, version, cmd, flags);

        // set the header length
        headerBuf.putInt(0, totalSize);

        NetlinkRequest netlinkRequest =
            NetlinkRequest.make(callback, translator, payload, seq, timeoutMillis);

        log.trace("[pid:{}] Sending message for id {}", pid(), seq);
        pendRequest(netlinkRequest, seq);

        if (bypassSendQueue) {
            processWriteToChannel(netlinkRequest);
        } else if (!writeQueue.offer(netlinkRequest)) {
            requestPool.release(payload);
            abortRequestQueueIsFull(netlinkRequest, seq);
        }
    }

    private int pid() {
        return getChannel().getLocalAddress().getPid();
    }

    private void pendRequest(NetlinkRequest req, int seq) {
        if (req.hasCallback()) {
            pendingRequests.put(seq, req);
        }
    }

    private int nextSequenceNumber() {
        int seq = sequenceGenerator.getAndIncrement();
        return seq == 0 ? nextSequenceNumber() : seq;
    }

    private void abortRequestQueueIsFull(NetlinkRequest req, int seq) {
        String msg = "Too many pending netlink requests";
        if (req.hasCallback()) {
            pendingRequests.remove(seq);
            /* Run the callback directly, because this runs out of the client's
             * thread, not the channel's: it's the client that failed to
             * put a request in the queue. */
            req.failed(new NetlinkException(
                NetlinkException.ERROR_SENDING_REQUEST, msg)).run();
        } else {
            log.info(msg);
        }
    }

    /*
     * Use blocking-write to send a batch of netlink requests and do
     * blocking reads until all their ACKs have been received back.
     *
     * Will throw if the channel is in non-blocking mode.
     *
     * NOT thread-safe.
     */
    public void doTransactionBatch() throws InterruptedException {
        if (! channel.isBlocking()) {
            throw new UnsupportedOperationException(
                "Blocking transactions on non-blocking channels are unsupported");
        }

        NetlinkRequest r;

        /* Wait five seconds for the 1st request to arrive, don't wait at all
         * after that. */
        for (r = writeQueue.poll(5000, TimeUnit.MILLISECONDS);
             r != null && ongoingTransaction.size() < maxBatchIoOps;
             r = writeQueue.poll()) {

            if (processWriteToChannel(r) <= 0)
                break;

            if (r.hasCallback())
                ongoingTransaction.add(r);
        }

        try {
            while (!ongoingTransaction.isEmpty()) {
                if (processReadFromChannel(null) <= 0)
                    break;
            }
        } catch (IOException e) {
            log.error("Netlink channel read() error", e);
        }

        expireOldRequests();
        dispatcher.endBatch();
        ongoingTransaction.clear();
    }

    public void handleWriteEvent() throws IOException {
        for (int i = 0; i < maxBatchIoOps; i++) {
            final NetlinkRequest request = writeQueue.poll();
            if (request == null)
                break;
            final int ret = processWriteToChannel(request);
            if (ret <= 0) {
                if (ret < 0) {
                    log.warn("NETLINK write() error: {}",
                            cLibrary.lib.strerror(Native.getLastError()));
                }
                break;
            }
        }
        expireOldRequests();
    }

    private int processWriteToChannel(final NetlinkRequest request) {
        if (request == null)
            return 0;

        ByteBuffer outBuf = request.releaseRequestPayload();
        if (outBuf == null)
            return 0;

        int bytes = 0;
        try {
            bytes = channel.write(outBuf);
            if (request.hasCallback())
                expirationQueue.add(request);
        } catch (IOException e) {
            log.warn("NETLINK write() exception: {}", e);
            if (request.hasCallback()) {
                pendingRequests.remove(request.seq);
                dispatcher.submit(request.failed(new NetlinkException(
                                  NetlinkException.ERROR_SENDING_REQUEST, e)));
            }
        } finally {
            requestPool.release(outBuf);
        }
        return bytes;
    }

    public void handleReadEvent(final TokenBucket tb) throws IOException {
        try {
            for (int i = 0; i < maxBatchIoOps; i++) {
                final int ret = processReadFromChannel(tb);
                if (ret <= 0) {
                    if (ret < 0) {
                        log.info("NETLINK read() error: {}",
                            cLibrary.lib.strerror(Native.getLastError()));
                    }
                    break;
                }
            }
        } catch (IOException e) {
            log.error("NETLINK read() exception: {}", e);
        } finally {
            if (!usingSharedNotificationHandler)
                endBatch();
            dispatcher.endBatch();
        }
    }

    protected void endBatch() {}

    private synchronized int processReadFromChannel(final TokenBucket tb)
            throws IOException {
        reply.clear();
        reply.order(ByteOrder.LITTLE_ENDIAN);

        // channel.read() returns # of bytes read.
        final int nbytes = channel.read(reply);
        if (nbytes == 0)
            return nbytes;

        reply.flip();
        reply.mark();

        int finalLimit = reply.limit();
        while (reply.remaining() >= NETLINK_HEADER_LEN) {
            // read the nlmsghdr and check for error
            int position = reply.position();

            int len = reply.getInt();           // length
            short type = reply.getShort();      // type
            short flags = reply.getShort();     // flags
            int seq = reply.getInt();           // sequence no.
            int pid = reply.getInt();           // pid

            int nextPosition = finalLimit;
            if (NLFlag.isMultiFlagSet(flags)) {
                reply.limit(position + len);
                nextPosition = position + len;
            }

            switch (type) {
                case NLMessageType.NOOP:
                    // skip to the next message
                    break;

                case NLMessageType.ERROR:
                    int error = reply.getInt();

                    // read header which caused the error
                    int errLen = reply.getInt();        // length
                    short errType = reply.getShort();   // type of error
                    short errFlags = reply.getShort();  // flags of the error
                    int errSeq = reply.getInt();        // sequence of the error
                    int errPid = reply.getInt();        // pid of the error

                    if (seq == 0) break; // should not happen

                    // An ACK is a NLMSG_ERROR with 0 as error code
                    if (error == 0) {
                        processSuccessfulRequest(removeRequest(seq));
                    } else {
                        processFailedRequest(seq, error);
                    }
                    break;

                case NLMessageType.DONE:
                    if (seq == 0) break; // should not happen
                    processSuccessfulRequest(removeRequest(seq));
                    break;

                default:
                    // read genl header
                    byte cmd = reply.get();      // command
                    byte ver = reply.get();      // version
                    reply.getShort();            // reserved

                    ByteBuffer payload = reply.slice();
                    payload.order(ByteOrder.nativeOrder());

                    if (seq == 0) {
                        // if the seq number is zero we are handling a PacketIn.
                        if (tb == null || tb.tryGet(1) == 1)
                            handleNotification(type, cmd, seq, pid, payload);
                        else
                            log.debug("Failed to get token; dropping packet");
                    } else  {
                        // otherwise we are processing an answer to a request.
                        processRequestAnswer(seq, flags, payload);
                    }
            }

            reply.limit(finalLimit);
            reply.position(nextPosition);
        }
        return nbytes;
    }

    private void processSuccessfulRequest(NetlinkRequest request) {
        if (request != null) {
            ongoingTransaction.remove(request);
            dispatcher.submit(request.successful());
        }
    }

    private void processRequestAnswer(int seq, short flag, ByteBuffer payload) {
        NetlinkRequest request = removeRequest(seq);
        if (request != null) {
            if (NLFlag.isMultiFlagSet(flag)) {
                // if the answer is made of multiple msgs, we clone the payload and
                // and we will process the request callback when the multi-msg ends.
                request.addAnswerFragment(cloneBuffer(payload));
                // We don't bother with placing the request back in the expiration
                // queue because we can't access it from this thread, and because
                // we know the reply is in flight.
                pendingRequests.put(seq, request);
            } else {
                // otherwise we process the callback directly with the payload.
                request.addAnswerFragment(payload);
                processSuccessfulRequest(request);
            }
        }
    }

    private void processFailedRequest(int seq, int error) {
        NetlinkRequest request = removeRequest(seq);
        if (request != null) {
            String errorMessage = cLibrary.lib.strerror(-error);
            NetlinkException err = new NetlinkException(-error, errorMessage);
            dispatcher.submit(request.failed(err));
        }
    }

    private NetlinkRequest removeRequest(int seq) {
        NetlinkRequest request = pendingRequests.remove(seq);
        if (request == null) {
            log.debug("[pid:{}] Reply handler for netlink request with id {} " +
                      "not found.", pid(), seq);
        }
        return request;
    }

    private ByteBuffer cloneBuffer(ByteBuffer from) {
        int origPos = from.position();
        ByteBuffer to = ByteBuffer.allocate(from.remaining());
        to.order(from.order());
        to.put(from);
        to.flip();
        from.position(origPos);
        return to;
    }

    protected abstract void handleNotification(short type, byte cmd,
                                               int seq, int pid,
                                               ByteBuffer buffers);

    private int serializeNetlinkHeader(ByteBuffer request, int seq,
                                       short family, byte version, byte cmd,
                                       short flags) {

        int startPos = request.position();

        // put netlink header
        request.putInt(0);              // nlmsg_len
        request.putShort(family);       // nlmsg_type
        request.putShort(flags);        // nlmsg_flags
        request.putInt(seq);            // nlmsg_seq
        request.putInt(pid());          // nlmsg_pid

        // put genl header
        request.put(cmd);               // cmd
        request.put(version);           // version
        request.putShort((short) 0);    // reserved

        return request.position() - startPos;
    }

    static public class NetlinkRequest implements Runnable {

        enum State {
          NotYet,
          Success,
          Failure,
          HasRun;
        }

        private List<ByteBuffer> inBuffers; // dont allocate if callback is null
        private ByteBuffer outBuffer;
        private final Callback<Object> userCallback;
        private final Function<List<ByteBuffer>,Object> translationFunction;
        public final long expirationTimeNanos;
        public final int seq;
        private Object cbData = null;
        private State state = State.NotYet;

        private NetlinkRequest(Callback<Object> callback,
                              Function<List<ByteBuffer>,Object> translationFunc,
                              ByteBuffer data,
                              int seq,
                              long timeoutMillis) {
            this.userCallback = callback;
            this.translationFunction = translationFunc;
            this.outBuffer = data;
            this.seq = seq;
            this.expirationTimeNanos = System.nanoTime() +
                TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        }

        public boolean hasCallback() {
            return userCallback != null;
        }

        public void addAnswerFragment(ByteBuffer buf) {
            if (inBuffers == null) {
                inBuffers = new ArrayList<>(1); // most requests not fragmented
            }
            inBuffers.add(buf);
        }

        public ByteBuffer releaseRequestPayload() {
            ByteBuffer payload = outBuffer;
            outBuffer = null;
            return payload;
        }

        @Override
        public void run() {
            synchronized(this) {
                try {
                    switch (state) {
                        case Success:
                            userCallback.onSuccess(cbData);
                            break;

                        case Failure:
                            @SuppressWarnings("unchecked")
                            NetlinkException e = (NetlinkException) cbData;
                            userCallback.onError(e);
                            break;

                        case NotYet:
                            throw new IllegalStateException(
                                "tried to run the user callback before " +
                                "completing the NetlinkRequest");

                        case HasRun:
                            throw new IllegalStateException(
                                "attempted to run the user callback " +
                                "of a NetlinkRequest more than once");
                    }
                } catch (Exception e) {
                    log.error("Error trying to run user callback", e);
                } finally {
                    state = State.HasRun;
                }
            }
        }

        public Runnable successful() {
            changeState(State.Success, translationFunction.apply(inBuffers));
            return this;
        }

        public Runnable failed(final NetlinkException e) {
            changeState(State.Failure, e);
            return this;
        }

        public Runnable expired() {
            NetlinkException.ErrorCode er = NetlinkException.ErrorCode.ETIMEOUT;
            String msg = "request #" + seq + " timeout";
            return failed(new NetlinkException(er, msg));
        }

        private void changeState(State nextState, Object data) {
            synchronized(this) {
                if (state == State.NotYet) {
                    cbData = data;
                    state = nextState;
                } else {
                    log.error("Attempted to create a runnable for the " +
                              "NetlinkRequest #{} more than once.", seq);
                }
            }
        }

        public static <T> NetlinkRequest make(Callback<T> callback,
                                              Function<List<ByteBuffer>,T> translator,
                                              ByteBuffer data,
                                              int seq,
                                              long timeoutMillis) {
            @SuppressWarnings("unchecked")
            Callback<Object> cb = (Callback<Object>) callback;
            @SuppressWarnings("unchecked")
            Function<List<ByteBuffer>,Object> func =
                (Function<List<ByteBuffer>,Object>) translator;
            return new NetlinkRequest(cb, func, data, seq, timeoutMillis);
        }
    }

    // A null value is interpreted by the comparator as a netlinkrequest with
    // infinite timeout, and is therefore "larger" than any non-null request.
    public static final Comparator<NetlinkRequest> requestComparator =
        new Comparator<NetlinkRequest>() {
            @Override
            public int compare(NetlinkRequest a, NetlinkRequest b) {
                long aExp = a != null ? a.expirationTimeNanos : Long.MAX_VALUE;
                long bExp = b != null ? b.expirationTimeNanos : Long.MAX_VALUE;
                return a == b ? 0 : Long.compare(aExp, bExp);
            }

            @Override
            public boolean equals(Object o) {
                return o == this;
            }
    };

    protected ByteBuffer getBuffer() {
        ByteBuffer buf = requestPool.take();
        buf.order(ByteOrder.nativeOrder());
        buf.clear();
        buf.position(NETLINK_HEADER_LEN);
        return buf;
    }

    protected Builder newMessage() {
        return new Builder(getBuffer());
    }
}
