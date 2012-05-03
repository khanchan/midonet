/*
 * Copyright 2011 Midokura KK
 */

package com.midokura.midolman.layer3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.Vector;
import java.util.concurrent.TimeUnit;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import com.midokura.midolman.AbstractController;
import com.midokura.midolman.ForwardingElement.Action;
import com.midokura.midolman.ForwardingElement.ForwardInfo;
import com.midokura.midolman.MockVRNController;
import com.midokura.midolman.eventloop.MockReactor;
import com.midokura.midolman.layer3.Route.NextHop;
import com.midokura.midolman.openflow.MidoMatch;
import com.midokura.midolman.openflow.MockControllerStub;
import com.midokura.midolman.packets.ARP;
import com.midokura.midolman.packets.Data;
import com.midokura.midolman.packets.Ethernet;
import com.midokura.midolman.packets.ICMP;
import com.midokura.midolman.packets.IPv4;
import com.midokura.midolman.packets.IntIPv4;
import com.midokura.midolman.packets.MAC;
import com.midokura.midolman.packets.UDP;
import com.midokura.midolman.rules.Condition;
import com.midokura.midolman.rules.ForwardNatRule;
import com.midokura.midolman.rules.JumpRule;
import com.midokura.midolman.rules.LiteralRule;
import com.midokura.midolman.rules.NatTarget;
import com.midokura.midolman.rules.ReverseNatRule;
import com.midokura.midolman.rules.Rule;
import com.midokura.midolman.rules.RuleResult;
import com.midokura.midolman.state.*;
import com.midokura.midolman.state.ChainZkManager.ChainConfig;
import com.midokura.midolman.util.Cache;
import com.midokura.midolman.util.CacheWithPrefix;
import com.midokura.midolman.util.Callback;
import com.midokura.midolman.util.MockCache;
import com.midokura.midolman.util.Net;

public class TestRouter {

    private UUID uplinkId;
    private int uplinkGatewayAddr;
    private int uplinkPortAddr;
    private MAC uplinkMacAddr;
    private Route uplinkRoute;
    private Router rtr;
    private ReplicatedRoutingTable rTable;
    private MockReactor reactor;
    private MockControllerStub controllerStub;
    private Map<Integer, PortDirectory.MaterializedRouterPortConfig> portConfigs;
    private Map<Integer, UUID> portNumToId;
    List<UUID> ruleIDs = new ArrayList<UUID>();
    private int publicDnatAddr;
    private int internDnatAddr;
    private MockVRNController controller;
    private ChainZkManager chainMgr;
    private RuleZkManager ruleMgr;

    protected Cache createCache() {
        return new MockCache();
    }

    @Before
    public void setUp() throws Exception {
        String basePath = "/midolman";
        ZkPathManager pathMgr = new ZkPathManager(basePath);
        Directory dir = new MockDirectory();
        dir.add(pathMgr.getBasePath(), null, CreateMode.PERSISTENT);
        // Add the various paths.
        dir.add(pathMgr.getChainsPath(), null, CreateMode.PERSISTENT);
        dir.add(pathMgr.getRulesPath(), null, CreateMode.PERSISTENT);
        dir.add(pathMgr.getRoutersPath(), null, CreateMode.PERSISTENT);
        dir.add(pathMgr.getRoutesPath(), null, CreateMode.PERSISTENT);
        dir.add(pathMgr.getPortsPath(), null, CreateMode.PERSISTENT);
        dir.add(pathMgr.getGrePath(), null, CreateMode.PERSISTENT);
        dir.add(pathMgr.getVRNPortLocationsPath(), null, CreateMode.PERSISTENT);
        PortZkManager portMgr = new PortZkManager(dir, basePath);
        RouteZkManager routeMgr = new RouteZkManager(dir, basePath);
        RouterZkManager routerMgr = new RouterZkManager(dir, basePath);
        chainMgr = new ChainZkManager(dir, basePath);
        ruleMgr = new RuleZkManager(dir, basePath);

        UUID rtrId = routerMgr.create();
        // TODO(pino): replace the following with a real implementation.
        Cache cache = new CacheWithPrefix(createCache(), rtrId.toString());
        reactor = new MockReactor();
        rTable = new ReplicatedRoutingTable(rtrId,
                     routerMgr.getRoutingTableDirectory(rtrId),
                     CreateMode.EPHEMERAL);
        rTable.start();
        controller = new MockVRNController(679, dir, basePath, null,
                IntIPv4.fromString("192.168.200.200"), "externalIdKey");
        rtr = new Router(rtrId, dir, basePath, reactor, cache, controller);
        controllerStub = new MockControllerStub();

        // Create ports in ZK.
        // Create one port that works as an uplink for the router.
        uplinkGatewayAddr = 0x0a0b0c0d;
        uplinkPortAddr = 0xb4000102; // 180.0.1.2
        int nwAddr = 0x00000000; // 0.0.0.0/0
        uplinkMacAddr = MAC.fromString("02:0a:08:06:04:02");
        PortDirectory.MaterializedRouterPortConfig portConfig =
                new PortDirectory.MaterializedRouterPortConfig(
                        rtrId, nwAddr, 0, uplinkPortAddr, uplinkMacAddr, null,
                        nwAddr, 0, null);
        uplinkId = portMgr.create(portConfig);
        uplinkRoute = new Route(nwAddr, 0, nwAddr, 0, NextHop.PORT, uplinkId,
                uplinkGatewayAddr, 1, null, rtrId);
        routeMgr.create(uplinkRoute);
        // Pretend the uplink port is managed by a remote controller.
        rTable.addRoute(uplinkRoute);

        // Create ports with id 1, 2, 3, 11, 12, 13, 21, 22, 23.
        // 1, 2, 3 will be in subnet 10.0.0.0/24
        // 11, 12, 13 will be in subnet 10.0.1.0/24
        // 21, 22, 23 will be in subnet 10.0.2.0/24
        // Each of the ports 'spoofs' a /30 range of its subnet, for example
        // port 21 will route to 10.0.2.4/30, 22 to 10.0.2.8/30, etc.
        // 1, 11, 21 are treated as remote ports, the others as local.
        portConfigs = new HashMap<Integer,
                                  PortDirectory.MaterializedRouterPortConfig>();
        portNumToId = new HashMap<Integer, UUID>();
        for (int i = 0; i < 3; i++) {
            // Nw address is 10.0.<i>.0/24
            nwAddr = 0x0a000000 + (i << 8);
            // All ports in this subnet share the same ip address: 10.0.<i>.1
            int portAddr = nwAddr + 1;
            for (int j = 1; j < 4; j++) {
                int portNum = i * 10 + j;
                // The port will route to 10.0.<i>.<j*4>/30
                int segmentAddr = nwAddr + (j * 4);
                portConfig = new PortDirectory.MaterializedRouterPortConfig(
                        rtrId, nwAddr, 24, portAddr, null, null, segmentAddr,
                        30, null);
                portConfigs.put(portNum, portConfig);
                UUID portId = portMgr.create(portConfig);
                // Map the port number to the portId for later use.
                portNumToId.put(portNum, portId);
                // Default route to port based on destination only.  Weight 2.
                Route rt = new Route(0, 0, segmentAddr, 30, NextHop.PORT, portId,
                        Route.NO_GATEWAY, 2, null, rtrId);
                routeMgr.create(rt);
                if (1 == j) {
                    // The first port's routes are added manually because the
                    // first port will be treated as remote.
                    rTable.addRoute(rt);
                }
                // Anything from 10.0.0.0/16 is allowed through.  Weight 1.
                rt = new Route(0x0a000000, 16, segmentAddr, 30, NextHop.PORT,
                        portId, Route.NO_GATEWAY, 1, null, rtrId);
                routeMgr.create(rt);
                if (1 == j) {
                    // The first port's routes are added manually because the
                    // first port will be treated as remote.
                    rTable.addRoute(rt);
                }
                // Anything from 11.0.0.0/24 is silently dropped.  Weight 1.
                rt = new Route(0x0b000000, 24, segmentAddr, 30,
                        NextHop.BLACKHOLE, null, 0, 1, null, rtrId);
                routeMgr.create(rt);
                // Anything from 12.0.0.0/24 is rejected (ICMP filter
                // prohibited).
                rt = new Route(0x0c000000, 24, segmentAddr, 30, NextHop.REJECT,
                        null, 0, 1, null, rtrId);
                routeMgr.create(rt);

                if (1 != j) {
                    // Except for the first port, add them locally.
                    rtr.addPort(portId);
                }
            } // end for-loop on j
        } // end for-loop on i
    }

    @Test
    public void testDropsIPv6() {
        short IPv6_ETHERTYPE = (short) 0x86DD;
        Ethernet eth = new Ethernet();
        eth.setSourceMACAddress(MAC.fromString("02:aa:bb:cc:dd:01"));
        eth.setDestinationMACAddress(MAC.fromString("02:aa:bb:cc:dd:02"));
        eth.setEtherType(IPv6_ETHERTYPE);
        eth.setPad(true);
        UUID port12Id = portNumToId.get(12);
        ForwardInfo fInfo = routePacket(port12Id, eth);
        checkForwardInfo(fInfo, Action.NOT_IPV4, null, 0);
    }

    public static Ethernet makeUDP(MAC dlSrc, MAC dlDst, int nwSrc,
            int nwDst, short tpSrc, short tpDst, byte[] data) {
        UDP udp = new UDP();
        udp.setDestinationPort(tpDst);
        udp.setSourcePort(tpSrc);
        udp.setPayload(new Data(data));
        IPv4 ip = new IPv4();
        ip.setDestinationAddress(nwDst);
        ip.setSourceAddress(nwSrc);
        ip.setProtocol(UDP.PROTOCOL_NUMBER);
        ip.setPayload(udp);
        Ethernet eth = new Ethernet();
        eth.setDestinationMACAddress(dlDst);
        eth.setSourceMACAddress(dlSrc);
        eth.setEtherType(IPv4.ETHERTYPE);
        eth.setPayload(ip);
        return eth;
    }

    public ForwardInfo routePacket(UUID inPortId, Ethernet ethPkt) {
        MidoMatch match = AbstractController.createMatchFromPacket(
                ethPkt, (short) 0);
        ForwardInfo fInfo = new ForwardInfo();
        fInfo.inPortId = inPortId;
        fInfo.flowMatch = match;
        fInfo.matchIn = match;
        fInfo.pktIn = ethPkt;
        try {
            rtr.process(fInfo);
        } catch (StateAccessException e) {
            // TODO(pino): clean this up.
            e.printStackTrace();
        }
        return fInfo;
    }

    public static void checkForwardInfo(ForwardInfo fInfo, Action action,
            UUID outPortId, int nextHopNwAddr) {
        Assert.assertEquals(action, fInfo.action);
        if (null == outPortId)
            Assert.assertNull(fInfo.outPortId);
        else
            Assert.assertEquals(outPortId, fInfo.outPortId);
        // TODO(pino): test that the L2 fields have been set properly.
        //Assert.assertEquals(nextHopNwAddr, fInfo.nextHopNwAddr);
    }

    @Test
    public void testForwardToUplink() {
        // Make a packet that comes in on port 23 (dlDst set to port 23's mac,
        // nwSrc inside 10.0.2.12/30) and has a nwDst that matches the uplink
        // port (e.g. anything outside 10.0.0.0/16).
        byte[] payload = new byte[] { (byte) 0x0a, (byte) 0x0b, (byte) 0x0c };
        UUID port23Id = portNumToId.get(23);
        L3DevicePort devPort23 = rtr.devicePorts.get(port23Id);
        Ethernet eth = makeUDP(MAC.fromString("02:00:11:22:00:01"),
                devPort23.getMacAddr(), 0x0a00020c, 0x11223344, (short) 1111,
                (short) 2222, payload);
        ForwardInfo fInfo = routePacket(port23Id, eth);
        // ARPs for the upstream router.
        Assert.assertEquals(Action.PAUSED, fInfo.action);
        // Send ARP reply from the next hop addr, 10.11.12.13.
        Ethernet arp = makeArpReply(MAC.fromString("02:04:06:08:0a:0c"),
                uplinkMacAddr, uplinkGatewayAddr, uplinkPortAddr);
        routePacket(uplinkId, arp);
        // Now original pkt gets forwarded.
        checkForwardInfo(fInfo, Action.FORWARD, uplinkId, uplinkGatewayAddr);
    }

    @Test
    public void testForwardBetweenDownlinks() {
        // Make a packet that comes in on port 23 (dlDst set to port 23's mac,
        // nwSrc inside 10.0.2.12/30) and has a nwDst that matches port 12
        // (i.e. inside 10.0.1.8/30).
        byte[] payload = new byte[] { (byte) 0x0a, (byte) 0x0b, (byte) 0x0c };
        UUID port23Id = portNumToId.get(23);
        UUID port12Id = portNumToId.get(12);
        L3DevicePort devPort23 = rtr.devicePorts.get(port23Id);
        Ethernet eth = makeUDP(MAC.fromString("02:00:11:22:00:01"),
                devPort23.getMacAddr(), 0x0a00020d, 0x0a000109, (short) 1111,
                (short) 2222, payload);
        ForwardInfo fInfo = routePacket(port23Id, eth);
        // Verify got paused to ARP.
        Assert.assertEquals(Action.PAUSED, fInfo.action);
        L3DevicePort devPort12 = rtr.devicePorts.get(port12Id);
        Ethernet arp = makeArpReply(MAC.fromString("02:04:06:08:0a:0c"),
                devPort12.getMacAddr(), 0x0a000109, devPort12.getIPAddr());
        routePacket(port12Id, arp);
        // Now original pkt gets forwarded.
        checkForwardInfo(fInfo, Action.FORWARD, port12Id, 0x0a000109);
    }

    @Test
    public void testBlackholeRoute() {
        // Make a packet that comes in on the uplink port from a nw address in
        // 11.0.0.0/24 and with a nwAddr that matches port 21 - in 10.0.2.4/30.
        byte[] payload = new byte[] { (byte) 0x0a, (byte) 0x0b, (byte) 0x0c };
        Ethernet eth = makeUDP(MAC.fromString("02:00:11:22:00:01"),
                MAC.fromString("02:00:11:22:00:01"), 0x0b0000ab,
                0x0a000207, (short) 1234, (short) 4321, payload);
        ForwardInfo fInfo = routePacket(uplinkId, eth);
        // TODO(pino): changed BLACKHOLE to DROP, check ICMP wasn't sent.
        TestRouter.checkForwardInfo(fInfo, Action.DROP, null, 0);
    }

    @Test
    public void testRejectRoute() {
        // Make a packet that comes in on the uplink port from a nw address in
        // 12.0.0.0/24 and with a nwAddr that matches port 21 - in 10.0.2.4/30.
        byte[] payload = new byte[] { (byte) 0x0a, (byte) 0x0b, (byte) 0x0c };
        // UUID port12Id = portNumToId.get(12);
        Ethernet eth = makeUDP(MAC.fromString("02:00:11:22:00:01"),
                MAC.fromString("02:00:11:22:00:01"), 0x0c0000ab,
                0x0a000207, (short) 1234, (short) 4321, payload);
        ForwardInfo fInfo = routePacket(uplinkId, eth);
        // TODO(pino): changed REJECT to DROP, check ICMP was sent.
        TestRouter.checkForwardInfo(fInfo, Action.DROP, null, 0);
    }

    @Test
    public void testNoRoute() throws KeeperException, InterruptedException {
        // First, pretend that the remote controller that manages the uplink
        // removes it or crashes. The uplink route is removed from the rTable.
        rTable.deleteRoute(uplinkRoute);
        // Make a packet that comes in on the port 3 (nwSrc in 10.0.0.12/30)
        // and is destined for 10.5.0.10.
        byte[] payload = new byte[] { (byte) 0x0a, (byte) 0x0b, (byte) 0x0c };
        UUID port3Id = portNumToId.get(3);
        Ethernet eth = makeUDP(MAC.fromString("02:00:11:22:00:01"),
                MAC.fromString("02:00:11:22:00:01"), 0x0a00000f,
                0x0a05000a, (short) 1234, (short) 4321, payload);
        ForwardInfo fInfo = routePacket(port3Id, eth);
        // TODO(pino): changed NO_ROUTE to DROP, check ICMP was sent.
        TestRouter.checkForwardInfo(fInfo, Action.DROP, null, 0);

        // Try forwarding between down-links.
        // Make a packet that comes in on port 3 (dlDst set to port 3's mac,
        // nwSrc inside 10.0.0.12/30) and has a nwDst that matches port 12
        // (i.e. inside 10.0.1.8/30).
        UUID port12Id = portNumToId.get(12);
        L3DevicePort devPort3 = rtr.devicePorts.get(port3Id);
        eth = makeUDP(MAC.fromString("02:00:11:22:00:01"),
                devPort3.getMacAddr(), 0x0a00000e, 0x0a00010b, (short) 1111,
                (short) 2222, payload);
        fInfo = routePacket(port3Id, eth);
        // Verify paused on ARP.
        Assert.assertEquals(Action.PAUSED, fInfo.action);
        L3DevicePort devPort12 = rtr.devicePorts.get(port12Id);
        Ethernet arp = makeArpReply(MAC.fromString("02:04:06:08:0a:0c"),
                devPort12.getMacAddr(), 0x0a00010b, devPort12.getIPAddr());
        routePacket(port12Id, arp);
        // Now original pkt gets forwarded.
        checkForwardInfo(fInfo, Action.FORWARD, port12Id, 0x0a00010b);
        // Now have the local controller remove port 12.
        rtr.removePort(port12Id);
        fInfo = routePacket(port3Id, eth);
        // TODO(pino): changed NO_ROUTE to DROP, check ICMP was sent.
        TestRouter.checkForwardInfo(fInfo, Action.DROP, null, 0);
    }

    @Test
    public void testICMP() throws Exception {
        // Make an ICMP echo request packet and send it to port 23.
        UUID port23Id = portNumToId.get(23);
        L3DevicePort devPort23 = rtr.devicePorts.get(port23Id);
        ICMP icmpReq = new ICMP();
        short id = -12345;
        short seq = -20202;
        byte[] data = new byte[] { (byte) 0xaa, (byte) 0xbb, (byte) 0xcc,
                (byte) 0xdd, (byte) 0xee, (byte) 0xff };
        icmpReq.setEchoRequest(id, seq, data);
        IPv4 ipReq = new IPv4();
        ipReq.setPayload(icmpReq);
        ipReq.setProtocol(ICMP.PROTOCOL_NUMBER);
        // The ping can come from anywhere if one of the next hops is a
        int senderIP = Net.convertStringAddressToInt("10.0.2.13");
        ipReq.setSourceAddress(senderIP);
        ipReq.setDestinationAddress(Net.convertStringAddressToInt("10.0.2.1"));
        Ethernet ethReq = new Ethernet();
        ethReq.setPayload(ipReq);
        ethReq.setEtherType(IPv4.ETHERTYPE);
        ethReq.setDestinationMACAddress(devPort23.getMacAddr());
        MAC senderMac = MAC.fromString("ab:cd:ef:01:23:45");
        ethReq.setSourceMACAddress(senderMac);
        ForwardInfo fInfo = routePacket(port23Id, ethReq);
        Assert.assertEquals(Action.CONSUMED, fInfo.action);
        // Verify controller was requested to send the packet.
        Assert.assertEquals(1, controller.generatedPackets.size());
        MockVRNController.GeneratedPacket genPkt = controller.generatedPackets.get(0);
        // TODO: Verify genPkt was sent on the right port.
        // Verify genPkt is the desired ping reply.
        Ethernet ethReply = genPkt.eth;
        Assert.assertEquals(IPv4.ETHERTYPE, ethReply.getEtherType());
        Assert.assertEquals(devPort23.getMacAddr(),
                ethReply.getSourceMACAddress());
        Assert.assertEquals(senderMac, ethReply.getDestinationMACAddress());
        IPv4 ipReply = (IPv4) ethReply.getPayload();
        Assert.assertEquals(ICMP.PROTOCOL_NUMBER, ipReply.getProtocol());
        Assert.assertEquals(devPort23.getVirtualConfig().portAddr,
                ipReply.getSourceAddress());
        Assert.assertEquals(senderIP, ipReply.getDestinationAddress());
        ICMP icmpReply = (ICMP) ipReply.getPayload();
        Assert.assertEquals(ICMP.CODE_NONE, icmpReply.getCode());
        Assert.assertEquals(ICMP.TYPE_ECHO_REPLY, icmpReply.getType());
        Assert.assertEquals(id, icmpReply.getIdentifier());
        Assert.assertEquals(seq, icmpReply.getSequenceNum());
        Assert.assertArrayEquals(data, icmpReply.getData());
    }

    static public class ArpCompletedCallback implements Callback<MAC> {
        List<MAC> macsReturned = new ArrayList<MAC>();

        @Override
        public void call(MAC mac) {
            macsReturned.add(mac);
        }
    }

    public static Ethernet makeArpRequest(MAC sha, int spa, int tpa) {
        ARP arp = new ARP();
        arp.setHardwareType(ARP.HW_TYPE_ETHERNET);
        arp.setProtocolType(ARP.PROTO_TYPE_IP);
        arp.setHardwareAddressLength((byte) 6);
        arp.setProtocolAddressLength((byte) 4);
        arp.setOpCode(ARP.OP_REQUEST);
        arp.setSenderHardwareAddress(sha);
        arp.setTargetHardwareAddress(MAC.fromString("00:00:00:00:00:00"));
        arp.setSenderProtocolAddress(IPv4.toIPv4AddressBytes(spa));
        arp.setTargetProtocolAddress(IPv4.toIPv4AddressBytes(tpa));
        Ethernet pkt = new Ethernet();
        pkt.setPayload(arp);
        pkt.setSourceMACAddress(sha);
        pkt.setDestinationMACAddress(MAC.fromString("ff:ff:ff:ff:ff:ff"));
        pkt.setEtherType(ARP.ETHERTYPE);
        //pkt.setPad(true);
        return pkt;
    }

    public static Ethernet makeArpReply(MAC sha, MAC tha, int spa, int tpa) {
        ARP arp = new ARP();
        arp.setHardwareType(ARP.HW_TYPE_ETHERNET);
        arp.setProtocolType(ARP.PROTO_TYPE_IP);
        arp.setHardwareAddressLength((byte) 6);
        arp.setProtocolAddressLength((byte) 4);
        arp.setOpCode(ARP.OP_REPLY);
        arp.setSenderHardwareAddress(sha);
        arp.setTargetHardwareAddress(tha);
        arp.setSenderProtocolAddress(IPv4.toIPv4AddressBytes(spa));
        arp.setTargetProtocolAddress(IPv4.toIPv4AddressBytes(tpa));
        // TODO(pino) logging.
        Ethernet pkt = new Ethernet();
        pkt.setPayload(arp);
        pkt.setSourceMACAddress(sha);
        pkt.setDestinationMACAddress(tha);
        pkt.setEtherType(ARP.ETHERTYPE);
        //pkt.setPad(true);
        return pkt;
    }

    /*
     * It is now possible to ARP for addresses out of remote ports, so
     * this test is mooted.   -- JLM
     * TODO: Verify whether Pino agrees.
    @Test(expected = IllegalArgumentException.class)
    public void testArpRequestNonLocalPort() throws StateAccessException {
        // Try to get the MAC for a nwAddr on port 11 (i.e. in 10.0.1.4/30).
        // Port 11 is not local to this controller (it was never added to the
        // router as a L3DevicePort). So the router can't ARP to it.
        ArpCompletedCallback cb = new ArpCompletedCallback();
        UUID port1Id = portNumToId.get(11);
        rtr.getMacForIp(port1Id, 0x0a000105, cb);
    }
     */

    @Test
    public void testArpRequestNonLocalAddress() throws StateAccessException {
        // Try to get the MAC via port 12 for an address that isn't in that
        // port's local network segment (i.e. not in 10.0.1.8/30).
        ArpCompletedCallback cb = new ArpCompletedCallback();
        UUID port1Id = portNumToId.get(12);
        rtr.getMacForIp(port1Id, 0x0a000105, cb);
        Assert.assertEquals(1, cb.macsReturned.size());
        Assert.assertNull(cb.macsReturned.get(0));
    }

    @Test
    public void testArpRequestGeneration() throws StateAccessException {
        // Try to get the MAC for a nwAddr on port 2 (i.e. in 10.0.0.8/30).
        ArpCompletedCallback cb = new ArpCompletedCallback();
        UUID port2Id = portNumToId.get(2);
        Assert.assertEquals(0, controllerStub.sentPackets.size());
        rtr.getMacForIp(port2Id, 0x0a00000a, cb);
        // There should now be an ARP request in the MockVRNController
        Assert.assertEquals(1, controller.generatedPackets.size());
        L3DevicePort devPort2 = rtr.devicePorts.get(port2Id);
        MockVRNController.GeneratedPacket pkt =
                 controller.generatedPackets.get(0);
        Assert.assertEquals(port2Id, pkt.portID);
        Ethernet expectedArp = makeArpRequest(devPort2.getMacAddr(),
                devPort2.getIPAddr(), 0x0a00000a);
        Assert.assertArrayEquals(expectedArp.serialize(), pkt.eth.serialize());

        // Verify that a second getMacForIp call doesn't trigger another ARP.
        rtr.getMacForIp(port2Id, 0x0a00000a, cb);
        Assert.assertEquals(1, controller.generatedPackets.size());

        // Verify that a getMacForIp call for a different address does trigger
        // another ARP request.
        reactor.incrementTime(2, TimeUnit.SECONDS);
        rtr.getMacForIp(port2Id, 0x0a000009, cb);
        Assert.assertEquals(2, controller.generatedPackets.size());
        pkt = controller.generatedPackets.get(1);
        Ethernet expectedArp2 = makeArpRequest(devPort2.getMacAddr(),
                devPort2.getIPAddr(), 0x0a000009);
        Assert.assertArrayEquals(expectedArp2.serialize(), pkt.eth.serialize());

        // Verify that the ARP request for the first address is re-sent at 10s.
        reactor.incrementTime(7, TimeUnit.SECONDS);
        Assert.assertEquals(2, controller.generatedPackets.size());
        reactor.incrementTime(1, TimeUnit.SECONDS);
        Assert.assertEquals(3, controller.generatedPackets.size());
        pkt = controller.generatedPackets.get(2);
        Assert.assertArrayEquals(expectedArp.serialize(), pkt.eth.serialize());

        // The ARP request for the second address is resent at 12s.
        reactor.incrementTime(2, TimeUnit.SECONDS);
        Assert.assertEquals(4, controller.generatedPackets.size());
        pkt = controller.generatedPackets.get(3);
        Assert.assertArrayEquals(expectedArp2.serialize(), pkt.eth.serialize());

        // Verify that the first ARP times out at 60s and triggers the callback
        // twice (because we called getMacForIp twice for the first address).
        reactor.incrementTime(47, TimeUnit.SECONDS);
        Assert.assertEquals(0, cb.macsReturned.size());
        reactor.incrementTime(1, TimeUnit.SECONDS);
        Assert.assertEquals(2, cb.macsReturned.size());
        Assert.assertNull(cb.macsReturned.get(0));
        Assert.assertNull(cb.macsReturned.get(1));
        // At 62 seconds, the second ARP times out and invokes the callback.
        reactor.incrementTime(2, TimeUnit.SECONDS);
        Assert.assertEquals(3, cb.macsReturned.size());
        Assert.assertNull(cb.macsReturned.get(2));
    }

    @Test
    public void testArpReplyProcessing() throws StateAccessException {
        // Try to get the MAC for a nwAddr on port 2 (i.e. in 10.0.0.8/30).
        ArpCompletedCallback cb = new ArpCompletedCallback();
        UUID port2Id = portNumToId.get(2);
        Assert.assertEquals(0, controller.generatedPackets.size());
        rtr.getMacForIp(port2Id, 0x0a00000a, cb);
        Assert.assertEquals(1, controller.generatedPackets.size());
        // Now create the ARP response form 10.0.0.10. Make up its mac address,
        // but use port 2's L2 and L3 addresses for the destination.
        L3DevicePort devPort2 = rtr.devicePorts.get(port2Id);
        MAC hostMac = MAC.fromString("02:00:11:22:33:44");
        Ethernet arpReplyPkt = makeArpReply(hostMac, devPort2.getMacAddr(),
                0x0a00000a, devPort2.getIPAddr());
        ForwardInfo fInfo = routePacket(port2Id, arpReplyPkt);
        checkForwardInfo(fInfo, Action.CONSUMED, null, 0);
        // Verify that the callback was triggered with the correct mac.
        Assert.assertEquals(1, cb.macsReturned.size());
        Assert.assertTrue(hostMac.equals(cb.macsReturned.get(0)));
        // The ARP should be stale after 1800 seconds. So any request after
        // that long should trigger another ARP request to refresh the entry.
        // However, the stale Mac can still be returned immediately.
        reactor.incrementTime(1800, TimeUnit.SECONDS);
        // At this point a call to getMacForIP won't trigger an ARP request.
        rtr.getMacForIp(port2Id, 0x0a00000a, cb);
        Assert.assertEquals(1, controller.generatedPackets.size());
        Assert.assertEquals(2, cb.macsReturned.size());
        Assert.assertTrue(hostMac.equals(cb.macsReturned.get(1)));
        reactor.incrementTime(1, TimeUnit.SECONDS);
        // At this point a call to getMacForIP should trigger an ARP request.
        rtr.getMacForIp(port2Id, 0x0a00000a, cb);
        Assert.assertEquals(2, controller.generatedPackets.size());
        Assert.assertEquals(3, cb.macsReturned.size());
        Assert.assertTrue(hostMac.equals(cb.macsReturned.get(2)));
        // Let's send an ARP response that refreshes the ARP cache entry.
        fInfo = routePacket(port2Id, arpReplyPkt);
        checkForwardInfo(fInfo, Action.CONSUMED, null, 0);
        // Now, if we wait 3599 seconds the cached response is stale but can
        // still be returned. Again, getMacForIp will trigger an ARP request.
        reactor.incrementTime(3599, TimeUnit.SECONDS);
        rtr.getMacForIp(port2Id, 0x0a00000a, cb);
        Assert.assertEquals(3, controller.generatedPackets.size());
        Assert.assertEquals(4, cb.macsReturned.size());
        Assert.assertTrue(hostMac.equals(cb.macsReturned.get(3)));
        // Now we increment the time by 1 second and the cached response
        // is expired (completely removed). Therefore getMacForIp will trigger
        // another ARP request and the callback has to wait for the response.
        reactor.incrementTime(1, TimeUnit.SECONDS);
        rtr.getMacForIp(port2Id, 0x0a00000a, cb);
        Assert.assertEquals(4, controller.generatedPackets.size());
        // No new callbacks because it waits for the ARP response.
        Assert.assertEquals(4, cb.macsReturned.size());
        // Say someone asks for the same mac again.
        reactor.incrementTime(1, TimeUnit.SECONDS);
        rtr.getMacForIp(port2Id, 0x0a00000a, cb);
        // No new ARP request since only one second passed since the last one.
        Assert.assertEquals(4, controller.generatedPackets.size());
        // Again no new callbacks because it must wait for the ARP response.
        Assert.assertEquals(4, cb.macsReturned.size());
        // Now send the ARP reply and the callback should be triggered twice.
        fInfo = routePacket(port2Id, arpReplyPkt);
        checkForwardInfo(fInfo, Action.CONSUMED, null, 0);
        Assert.assertEquals(6, cb.macsReturned.size());
        Assert.assertTrue(hostMac.equals(cb.macsReturned.get(4)));
        Assert.assertTrue(hostMac.equals(cb.macsReturned.get(5)));
    }

    @Test
    public void testArpRequestProcessing() {
        // Checks that we correctly respond to a peer's ARP request with
        // an ARP reply.
        // Let's work with port 2 whose subnet is 10.0.0.0/24 and whose segment
        // is 10.0.0.8/30.  Assume the requests are coming from 10.0.0.9.
        int arpSpa = 0x0a000009;
        UUID port2Id = portNumToId.get(2);
        // Check for addresses inside the subnet but outside the segment.
        checkPeerArp(port2Id, arpSpa, 0x0a000005, true);
        checkPeerArp(port2Id, arpSpa, 0x0a00000e, true);
        checkPeerArp(port2Id, arpSpa, 0x0a000041, true);
        // Outside the subnet.
        checkPeerArp(port2Id, arpSpa, 0x0a000201, false);
        checkPeerArp(port2Id, arpSpa, 0x0a030201, false);
        // Inside the segment.
        checkPeerArp(port2Id, arpSpa, 0x0a000008, false);
        checkPeerArp(port2Id, arpSpa, 0x0a00000a, false);
        checkPeerArp(port2Id, arpSpa, 0x0a00000b, false);
        // Exactly the port's own address.
        checkPeerArp(port2Id, arpSpa, 0x0a000001, true);
    }

    /**
     * Check that the router does/doesn't reply to an ARP request for the given
     * target protocol address.
     *
     * @param portId
     *            The port at which the ARP request is received.
     * @param arpTpa
     *            The network address that request wants to resolve.
     * @param arpSpa
     *            The network address issuing the request.
     * @param shouldReply
     *            Whether we expect the router to reply.
     */
    public void checkPeerArp(UUID portId, int arpSpa, int arpTpa,
            boolean shouldReply) {
        controller.generatedPackets.clear();
        MAC arpSha = MAC.fromString("02:aa:aa:aa:aa:01");
        Ethernet eth = makeArpRequest(arpSha, arpSpa, arpTpa);
        ForwardInfo fInfo = routePacket(portId, eth);
        checkForwardInfo(fInfo, Action.CONSUMED, null, 0);
        if (shouldReply) {
            Assert.assertEquals(1, controller.generatedPackets.size());
            MockVRNController.GeneratedPacket pkt =
                        controller.generatedPackets.get(0);
            L3DevicePort devPort = rtr.devicePorts.get(portId);
            Assert.assertEquals(portId, pkt.portID);
            Ethernet expArpReply = makeArpReply(devPort.getMacAddr(), arpSha,
                    arpTpa, arpSpa);
            Assert.assertArrayEquals(expArpReply.serialize(), pkt.eth.serialize());
        } else {
            Assert.assertEquals(0, controller.generatedPackets.size());
        }
    }

    private void deleteRules() throws StateAccessException {
        for (UUID ruleID : ruleIDs) {
            chainMgr.delete(ruleID);
        }
        ruleIDs.clear();
    }

    private void createRules() throws StateAccessException,
            ZkStateSerializationException, RuleIndexOutOfBoundsException {
        UUID preChainId = chainMgr.create(new ChainConfig(Router.PRE_ROUTING));
        String srcFilterChainName = "filterSrcByPortId";
        UUID srcFilterChainId = chainMgr.create(new ChainConfig(srcFilterChainName));
        UUID postChainId = chainMgr.create(new ChainConfig(Router.POST_ROUTING));
        ruleIDs.add(preChainId);
        ruleIDs.add(srcFilterChainId);
        ruleIDs.add(postChainId);
        // Create a bunch of arbitrary rules:
        //  * 'down-ports' can only receive packets from hosts 'local' to them.
        //  * arbitrarily drop flows to some hosts that are 'quarantined'.
        //  * arbitrarily don't allow some ports to communicate with each other.
        // Apply Dnat.
        Condition cond;
        // Here's the pre-routing chain.
        List<Rule> preChain = new Vector<Rule>();
        // Down-ports can only receive packets from network addresses that are
        // 'local' to them. This chain drops packets that don't conform.
        List<Rule> srcFilterChain = new Vector<Rule>();
        Iterator<Map.Entry<Integer,
                           PortDirectory.MaterializedRouterPortConfig>> iter =
                                        portConfigs.entrySet().iterator();
        int i = 1;
        Rule r;
        while (iter.hasNext()) {
            Map.Entry<Integer, PortDirectory.MaterializedRouterPortConfig>
                                        entry = iter.next();
            UUID portId = portNumToId.get(entry.getKey());
            PortDirectory.MaterializedRouterPortConfig config = entry.getValue();
            // A down-port can only receive packets from network addresses that
            // are 'local' to the port.
            cond = new Condition();
            cond.inPortIds = new HashSet<UUID>();
            cond.inPortIds.add(portId);
            cond.nwSrcIp = config.localNwAddr;
            cond.nwSrcLength = (byte) config.localNwLength;
            r = new LiteralRule(cond, RuleResult.Action.RETURN,
                    srcFilterChainId, i);
            i++;
            ruleMgr.create(r);
            srcFilterChain.add(r);
        }
        // The localAddresses chain should accept entering the uplink from
        // outside 10.0.0.0/16.
        cond = new Condition();
        cond.inPortIds = new HashSet<UUID>();
        cond.inPortIds.add(uplinkId);
        cond.nwSrcIp = 0x0a000000;
        cond.nwSrcLength = 16;
        cond.nwSrcInv = true;
        r = new LiteralRule(cond, RuleResult.Action.RETURN, srcFilterChainId, i);
        i++;
        ruleMgr.create(r);
        srcFilterChain.add(r);
        // Finally, anything that gets to the end of this chain is dropped.
        r = new LiteralRule(new Condition(), RuleResult.Action.DROP,
                srcFilterChainId, i);
        i++;
        ruleMgr.create(r);
        srcFilterChain.add(r);

        i = 1;
        // The pre-routing chain needs a jump rule to the localAddressesChain
        r = new JumpRule(new Condition(), srcFilterChainName, preChainId, i);
        i++;
        ruleMgr.create(r);
        preChain.add(r);
        // Add a dnat rule to map udp requests coming from the uplink for
        // 180.0.0.1:80 to 10.0.2.6/32.
        publicDnatAddr = 0xb4000001;
        internDnatAddr = 0x0a000206;
        short natPublicTpPort = 80;
        Set<NatTarget> nats = new HashSet<NatTarget>();
        nats.add(new NatTarget(internDnatAddr, internDnatAddr, natPublicTpPort,
                natPublicTpPort));
        cond = new Condition();
        cond.inPortIds = new HashSet<UUID>();
        cond.inPortIds.add(uplinkId);
        cond.nwProto = UDP.PROTOCOL_NUMBER;
        cond.nwDstIp = publicDnatAddr;
        cond.nwDstLength = 32;
        cond.tpDstStart = natPublicTpPort;
        cond.tpDstEnd = natPublicTpPort;
        r = new ForwardNatRule(cond, RuleResult.Action.ACCEPT, preChainId, i,
                true /* dnat */, nats);
        i++;
        ruleMgr.create(r);
        preChain.add(r);

        i = 1;
        List<Rule> postChain = new Vector<Rule>();
        // Now add a post-routing rule that stops communication between
        // ports 1, 11, and 21. None of them can send to any of the others.
        cond = new Condition();
        cond.inPortIds = new HashSet<UUID>();
        cond.inPortIds.add(portNumToId.get(1));
        cond.inPortIds.add(portNumToId.get(11));
        cond.inPortIds.add(portNumToId.get(21));
        cond.outPortIds = new HashSet<UUID>();
        cond.outPortIds.add(portNumToId.get(1));
        cond.outPortIds.add(portNumToId.get(11));
        cond.outPortIds.add(portNumToId.get(21));
        r = new LiteralRule(cond, RuleResult.Action.DROP, postChainId, i);
        i++;
        ruleMgr.create(r);
        postChain.add(r);
        // Now add a post-routing rule to quarantine host 10.0.2.5.
        cond = new Condition();
        cond.nwDstIp = 0x0a000205;
        cond.nwDstLength = 32;
        r = new LiteralRule(cond, RuleResult.Action.REJECT, postChainId, i);
        i++;
        ruleMgr.create(r);
        // Try to reverse dnat udp packets from 10.0.2.6/32.
        cond = new Condition();
        cond.nwProto = UDP.PROTOCOL_NUMBER;
        cond.nwSrcIp = internDnatAddr;
        cond.nwSrcLength = 32;
        cond.tpSrcStart = natPublicTpPort;
        cond.tpSrcEnd = natPublicTpPort;
        r = new ReverseNatRule(cond, RuleResult.Action.CONTINUE, postChainId,
                i, true /* dnat */);
        i++;
        ruleMgr.create(r);
        postChain.add(r);
    }

    @Test
    public void testFilterBadSrcForPort() throws StateAccessException,
            ZkStateSerializationException, RuleIndexOutOfBoundsException {
        // Make a packet that comes in on port 23 but with a nwSrc outside
        // the expected range (10.0.2.12/30) and a nwDst that matches port 12
        // (i.e. inside 10.0.1.8/30).
        byte[] payload = new byte[] { (byte) 0x0a, (byte) 0x0b, (byte) 0x0c };
        UUID port23Id = portNumToId.get(23);
        UUID port12Id = portNumToId.get(12);
        L3DevicePort devPort23 = rtr.devicePorts.get(port23Id);
        L3DevicePort devPort12 = rtr.devicePorts.get(port12Id);
        Ethernet badEthTo12 = makeUDP(
                MAC.fromString("02:00:11:22:00:01"), devPort23.getMacAddr(),
                0x0a000202, 0x0a000109, (short) 1111, (short) 2222, payload);
        // This packet has a good source address and is always routed correctly.
        Ethernet goodEthTo12 = makeUDP(
                MAC.fromString("02:00:11:22:00:01"), devPort23.getMacAddr(),
                0x0a00020f, 0x0a000109, (short) 1111, (short) 2222, payload);
        ForwardInfo fInfo = routePacket(port23Id, badEthTo12);
        // Packet gets hung up, pended on ARP.
        Assert.assertEquals(Action.PAUSED, fInfo.action);
        Ethernet arp = makeArpReply(MAC.fromString("02:04:06:08:0a:0c"),
                devPort12.getMacAddr(), 0x0a000109, devPort12.getIPAddr());
        routePacket(port12Id, arp);
        checkForwardInfo(fInfo, Action.FORWARD, port12Id, 0x0a000109);
        fInfo = routePacket(port23Id, goodEthTo12);
        checkForwardInfo(fInfo, Action.FORWARD, port12Id, 0x0a000109);

        // Make a packet that comes in on port 12 but with a nwSrc outside
        // the expected range (10.0.1.8/30) and a nwDst that matches port 2
        // (i.e. inside 10.0.0.8/30).
        UUID port2Id = portNumToId.get(2);
        L3DevicePort devPort2 = rtr.devicePorts.get(port2Id);
        Ethernet badEthTo2 = makeUDP(
                MAC.fromString("02:00:11:22:00:01"), devPort12.getMacAddr(),
                0x0a000103, 0x0a00000a, (short) 1111, (short) 2222, payload);
        // This packet has a good source address and is always routed correctly.
        Ethernet goodEthTo2 = makeUDP(
                MAC.fromString("02:00:11:22:00:01"), devPort12.getMacAddr(),
                0x0a000109, 0x0a00000a, (short) 1111, (short) 2222, payload);
        fInfo = routePacket(port12Id, badEthTo2);
        // Packet gets hung up, pended on ARP.
        Assert.assertEquals(Action.PAUSED, fInfo.action);
        Ethernet arp2 = makeArpReply(MAC.fromString("02:04:06:08:0a:de"),
                devPort2.getMacAddr(), 0x0a00000a, devPort2.getIPAddr());
        routePacket(port2Id, arp2);
        checkForwardInfo(fInfo, Action.FORWARD, port2Id, 0x0a00000a);
        fInfo = routePacket(port12Id, goodEthTo2);
        checkForwardInfo(fInfo, Action.FORWARD, port2Id, 0x0a00000a);

        // Make a packet that comes in on port 13 but with a nwSrc outside
        // the expected range (10.0.1.12/30) and a nwDst that matches the
        // uplink (e.g. 144.0.16.3).
        UUID port13Id = portNumToId.get(13);
        L3DevicePort devPort13 = rtr.devicePorts.get(port13Id);
        Ethernet badEthToUplink = makeUDP(
                MAC.fromString("02:00:11:22:00:01"),
                devPort13.getMacAddr(), 0x0a0001d2, 0x90001003,
                (short) 1111, (short) 2222, payload);
        // This packet has a good source address and is always routed correctly.
        Ethernet goodEthToUplink = makeUDP(
                MAC.fromString("02:00:11:22:00:01"),
                devPort13.getMacAddr(), 0x0a00010c, 0x90001003,
                (short) 1111, (short) 2222, payload);
        fInfo = routePacket(port13Id, badEthToUplink);
        // Packet gets hung up, pended on ARP.
        Assert.assertEquals(Action.PAUSED, fInfo.action);
        Ethernet arp3 = makeArpReply(MAC.fromString("02:c4:a6:b8:ea:df"),
                devPort13.getMacAddr(), uplinkGatewayAddr,
                devPort13.getIPAddr());
        routePacket(port13Id, arp3);
        checkForwardInfo(fInfo, Action.FORWARD, uplinkId, uplinkGatewayAddr);
        fInfo = routePacket(port13Id, goodEthToUplink);
        checkForwardInfo(fInfo, Action.FORWARD, uplinkId, uplinkGatewayAddr);

        // After adding the filtering rules the 'bad' packets are dropped but
        // the 'good' ones are still forwarded.
        createRules();
        fInfo = routePacket(port23Id, badEthTo12);
        // TODO(pino): changed BLACKHOLE to DROP, check ICMP wasn't sent.
        checkForwardInfo(fInfo, Action.DROP, null, 0);
        fInfo = routePacket(port23Id, goodEthTo12);
        checkForwardInfo(fInfo, Action.FORWARD, port12Id, 0x0a000109);
        fInfo = routePacket(port12Id, badEthTo2);
        // TODO(pino): changed BLACKHOLE to DROP, check ICMP wasn't sent.
        checkForwardInfo(fInfo, Action.DROP, null, 0);
        fInfo = routePacket(port12Id, goodEthTo2);
        checkForwardInfo(fInfo, Action.FORWARD, port2Id, 0x0a00000a);
        fInfo = routePacket(port13Id, badEthToUplink);
        // TODO(pino): changed BLACKHOLE to DROP, check ICMP wasn't sent.
        checkForwardInfo(fInfo, Action.DROP, null, 0);
        fInfo = routePacket(port13Id, goodEthToUplink);
        checkForwardInfo(fInfo, Action.FORWARD, uplinkId, uplinkGatewayAddr);

        // Now remove the filterSrcByPortId rules and verify that all the
        // packets are again forwarded.
        deleteRules();
        fInfo = routePacket(port23Id, badEthTo12);
        checkForwardInfo(fInfo, Action.FORWARD, port12Id, 0x0a000109);
        fInfo = routePacket(port12Id, goodEthTo2);
        checkForwardInfo(fInfo, Action.FORWARD, port2Id, 0x0a00000a);
        fInfo = routePacket(port13Id, badEthToUplink);
        checkForwardInfo(fInfo, Action.FORWARD, uplinkId, uplinkGatewayAddr);
        fInfo = routePacket(port23Id, goodEthTo12);
        checkForwardInfo(fInfo, Action.FORWARD, port12Id, 0x0a000109);
        fInfo = routePacket(port12Id, badEthTo2);
        checkForwardInfo(fInfo, Action.FORWARD, port2Id, 0x0a00000a);
        fInfo = routePacket(port13Id, goodEthToUplink);
        checkForwardInfo(fInfo, Action.FORWARD, uplinkId, uplinkGatewayAddr);
    }

    @Test
    public void testFilterBadDestinations() throws StateAccessException,
            ZkStateSerializationException, RuleIndexOutOfBoundsException {
        // Send a packet from port 13 to port 21.
        byte[] payload = new byte[] { (byte) 0x0a, (byte) 0x0b, (byte) 0x0c };
        UUID port13Id = portNumToId.get(13);
        L3DevicePort devPort13 = rtr.devicePorts.get(port13Id);
        UUID port21Id = portNumToId.get(21);
        PortDirectory.MaterializedRouterPortConfig portCfg21 =
                portConfigs.get(21);
        Ethernet ethTo21 = makeUDP(MAC.fromString("02:00:11:22:00:01"),
                devPort13.getMacAddr(), 0x0a000106, 0x0a000207,
                (short) 1111, (short) 2222, payload);
        ForwardInfo fInfo = routePacket(port13Id, ethTo21);
        // Gets stuck on ARP.
        Assert.assertEquals(Action.PAUSED, fInfo.action);
        Ethernet arp = makeArpReply(MAC.fromString("02:04:06:08:0a:0c"),
                portCfg21.getHwAddr(), 0x0a000207, portCfg21.portAddr);
        routePacket(port21Id, arp);
        checkForwardInfo(fInfo, Action.FORWARD, port21Id, 0x0a000207);

        // Send a packet 10.0.2.5 from the uplink.
        Ethernet ethToQuarantined = makeUDP(
                MAC.fromString("02:00:11:22:00:01"), devPort13.getMacAddr(),
                0x94001006, 0x0a000205, (short) 1111, (short) 2222, payload);
        fInfo = routePacket(port13Id, ethToQuarantined);
        // Gets stuck on ARP.
        Assert.assertEquals(Action.PAUSED, fInfo.action);
        arp = makeArpReply(MAC.fromString("02:a4:b6:b8:da:dc"),
                portCfg21.getHwAddr(), 0x0a000205, portCfg21.portAddr);
        routePacket(port21Id, arp);
        checkForwardInfo(fInfo, Action.FORWARD, port21Id, 0x0a000205);

        // After adding the filtering rules these packets are dropped.
        createRules();
        fInfo = routePacket(port13Id, ethTo21);
        // TODO(pino): changed BLACKHOLE to DROP, check ICMP wasn't sent.
        checkForwardInfo(fInfo, Action.DROP, null, 0);
        fInfo = routePacket(uplinkId, ethToQuarantined);
        // TODO(pino): changed REJECT to DROP, check ICMP was sent.
        checkForwardInfo(fInfo, Action.DROP, null, 0);
        deleteRules();
    }

    @Test
    public void testDnat() throws StateAccessException,
            ZkStateSerializationException, RuleIndexOutOfBoundsException {
        createRules();
        // Send a packet from 10.0.2.6 to 192.11.11.10.
        int extNwAddr = 0xc00b0b0a;
        byte[] payload = new byte[] { (byte) 0x0a, (byte) 0x0b, (byte) 0x0c };
        UUID port21Id = portNumToId.get(21);
        PortDirectory.MaterializedRouterPortConfig portCfg21 =
                portConfigs.get(21);
        Ethernet ethToExtAddr = makeUDP(MAC.fromString("02:00:11:22:00:01"),
                portCfg21.getHwAddr(), 0x0a000206, extNwAddr,
                (short) 80, (short) 2222, payload);
        ForwardInfo fInfo = routePacket(port21Id, ethToExtAddr);
        // Pended on ARP.
        Assert.assertEquals(Action.PAUSED, fInfo.action);
        MAC gatewayMac = MAC.fromString("02:14:96:98:4a:1c");
        Ethernet arp = makeArpReply(gatewayMac, uplinkMacAddr,
                                    uplinkGatewayAddr, uplinkPortAddr);
        routePacket(uplinkId, arp);
        checkForwardInfo(fInfo, Action.FORWARD, uplinkId, uplinkGatewayAddr);
        // No translation has occurred - the in/out OFMatch's are the same
        // except the router has overwritten the MACs.
        MidoMatch expectedMatch = fInfo.matchIn.clone();
        expectedMatch.setDataLayerDestination(gatewayMac);
        expectedMatch.setDataLayerSource(uplinkMacAddr);
        Assert.assertEquals(expectedMatch, fInfo.matchOut);

        // Now send a packet from 192.11.11.10 to the dnat-ed address
        // (180.0.0.1:80).
        Ethernet ethToPublicAddr = makeUDP(gatewayMac, uplinkMacAddr,
                extNwAddr, publicDnatAddr, (short) 2222, (short) 80, payload);
        fInfo = routePacket(uplinkId, ethToPublicAddr);
        // Pended on ARP.
        Assert.assertEquals(Action.PAUSED, fInfo.action);
        arp = makeArpReply(MAC.fromString("02:aa:ab:ba:cd:cc"),
                           portCfg21.getHwAddr(), internDnatAddr,
                           portCfg21.portAddr);
        routePacket(port21Id, arp);
        checkForwardInfo(fInfo, Action.FORWARD, port21Id, internDnatAddr);
        Assert.assertEquals(internDnatAddr,
                fInfo.matchOut.getNetworkDestination());
        Assert.assertEquals(publicDnatAddr,
                fInfo.matchIn.getNetworkDestination());
        // Since the destination UDP port is 80 before and after the mapping,
        // the match should be the same before/after routing except for nwDst
        // and the MACs.
        MAC dummyMac = new MAC();
        fInfo.matchOut.setNetworkDestination(0);
        fInfo.matchIn.setNetworkDestination(0);
        fInfo.matchOut.setDataLayerSource(dummyMac);
        fInfo.matchOut.setDataLayerDestination(dummyMac);
        fInfo.matchIn.setDataLayerSource(dummyMac);
        fInfo.matchIn.setDataLayerDestination(dummyMac);
        Assert.assertEquals(fInfo.matchIn, fInfo.matchOut);

        // Now send the original packet from 10.0.2.6. This time it should
        // be reverse dnat'ed.
        fInfo = routePacket(port21Id, ethToExtAddr);
        checkForwardInfo(fInfo, Action.FORWARD, uplinkId, uplinkGatewayAddr);
        // For this packet, the source network address changed.
        Assert.assertEquals(internDnatAddr, fInfo.matchIn.getNetworkSource());
        Assert.assertEquals(publicDnatAddr, fInfo.matchOut.getNetworkSource());
        // Since the source UDP port is 80 before and after the mapping,
        // the match should be the same before/after routing except for nwSrc
        // and the MACs.
        fInfo.matchOut.setNetworkSource(0);
        fInfo.matchIn.setNetworkSource(0);
        fInfo.matchOut.setDataLayerSource(dummyMac);
        fInfo.matchOut.setDataLayerDestination(dummyMac);
        fInfo.matchIn.setDataLayerSource(dummyMac);
        fInfo.matchIn.setDataLayerDestination(dummyMac);
        Assert.assertEquals(fInfo.matchIn, fInfo.matchOut);
        deleteRules();
    }
}
