/*
 * Copyright 2019-present Open Networking Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package nctu.winlab.proxyarpapp;

import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Deactivate;
import org.apache.felix.scr.annotations.Property;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.ReferenceCardinality;
import org.apache.felix.scr.annotations.Service;

import org.onlab.packet.Ethernet;
import org.onlab.packet.MacAddress;
import org.onlab.packet.TpPort;
import org.onlab.packet.IPv4;
import org.onlab.packet.ARP;
import org.onlab.packet.IpAddress;
import org.onlab.packet.Ip4Address;
import org.onlab.packet.IpPrefix;

import org.onlab.util.KryoNamespace;

import org.onosproject.core.ApplicationId;
import org.onosproject.core.CoreService;

import org.onosproject.net.packet.PacketService;
import org.onosproject.net.packet.PacketProcessor;
import org.onosproject.net.packet.PacketContext;
import org.onosproject.net.packet.InboundPacket;
import org.onosproject.net.packet.OutboundPacket;
import org.onosproject.net.packet.DefaultOutboundPacket;
import org.onosproject.net.packet.PacketPriority;

import org.onosproject.net.PortNumber;
import org.onosproject.net.DeviceId;

import org.onosproject.net.topology.GraphDescription;
import org.onosproject.net.topology.Topology;
import org.onosproject.net.topology.TopologyService;
import org.onosproject.net.topology.TopologyGraph;
import org.onosproject.net.topology.TopologyVertex;
import org.onosproject.net.topology.TopologyEdge;

import org.onosproject.net.flow.TrafficSelector;
import org.onosproject.net.flow.TrafficTreatment;
import org.onosproject.net.flow.DefaultTrafficSelector;
import org.onosproject.net.flow.DefaultTrafficTreatment;

import org.onosproject.net.flowobjective.ForwardingObjective;
import org.onosproject.net.flowobjective.DefaultForwardingObjective;
import org.onosproject.net.flowobjective.FlowObjectiveService;

import org.onosproject.net.Host;
import org.onosproject.net.host.HostService;

import org.onosproject.net.HostLocation;

import org.onosproject.net.Link;
import org.onosproject.net.link.LinkService;

import org.onosproject.net.ConnectPoint;

import org.onosproject.net.edge.EdgePortService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.onosproject.store.serializers.KryoNamespaces;

import org.onosproject.store.service.EventuallyConsistentMap;
import org.onosproject.store.service.StorageService;
import org.onosproject.store.service.WallClockTimestamp;
import org.onosproject.store.service.MultiValuedTimestamp;

import org.onosproject.net.config.ConfigFactory;
import org.onosproject.net.config.NetworkConfigEvent;
import org.onosproject.net.config.NetworkConfigListener;
import org.onosproject.net.config.NetworkConfigRegistry;

import javafx.util.Pair;

import java.util.HashMap;
import java.util.Set;

import com.google.common.collect.ImmutableSet;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.ListIterator;

import java.nio.ByteBuffer;

import java.lang.Integer;




/**
 * Skeletal ONOS application component.
 */
@Component(immediate = true)


public class AppComponent {

    private final Logger log = LoggerFactory.getLogger(getClass());

    private ApplicationId appId;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected NetworkConfigRegistry cfgService;

	@Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
	protected CoreService coreService;

	@Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
	protected PacketService packetService;

	@Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
	protected StorageService storageService;

	@Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
	protected FlowObjectiveService flowObjectiveService;

	@Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
	protected TopologyService topologyService;

	@Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
	protected HostService hostService;

	@Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
	protected LinkService linkService;

	@Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
	protected EdgePortService edgePortService;

	private int flowPriority = 10;

	private MyProcessor processor = new MyProcessor();

	private TopologyVertex dhcpSwitch;
	private PortNumber dhcpPort;

	private HashMap<MacAddress, Integer> macToIpv4 = new HashMap<MacAddress, Integer>();
	private HashMap<MacAddress, Pair<DeviceId, PortNumber>> macToDevicePort = new HashMap<MacAddress, Pair<DeviceId, PortNumber>>();




    @Activate
    protected void activate() {
    	appId = coreService.registerApplication("nctu.winlab.proxyarpapp");

    	KryoNamespace.Builder metricSerializer = KryoNamespace.newBuilder()
    			.register(KryoNamespaces.API)
    			.register(MultiValuedTimestamp.class);

    	packetService.addProcessor(processor, PacketProcessor.director(2));

    	requestIntercepts();
    	
        log.info("Started");
    }

    @Deactivate
    protected void deactivate() {
        log.info("Stopped");
    }

    private void requestIntercepts() {
    	TrafficSelector.Builder selector = DefaultTrafficSelector.builder();
    	selector.matchEthType(Ethernet.TYPE_ARP);
    	packetService.requestPackets(selector.build(), PacketPriority.REACTIVE, appId);

    }

    private void withdrawIntercepts() {
	 	TrafficSelector.Builder selector = DefaultTrafficSelector.builder();
	 	selector.matchEthType(Ethernet.TYPE_ARP);
	 	packetService.cancelPackets(selector.build(), PacketPriority.REACTIVE, appId);

    }


    Set<TopologyVertex> vertexes;

    private class MyProcessor implements PacketProcessor {

    	@Override
    	public void process(PacketContext context) {
    		
    		//ignore other control packet
    		if (context.isHandled()) {
    			return;
    		}

    		//make some delay
    		for(int i = 0; i < 100000000; i++){}

    		//fetch the src switch and dst switch
    		InboundPacket pkt = context.inPacket();
    		Ethernet ethPkt = pkt.parsed();
    		MacAddress srcMacAddress = ethPkt.getSourceMAC();
    		MacAddress dstMacAddress = ethPkt.getDestinationMAC();
    		PortNumber inputPort = context.inPacket().receivedFrom().port();
    		DeviceId deviceId = context.inPacket().receivedFrom().deviceId();
    		
   			ARP arpPacket = (ARP) ethPkt.getPayload();

   			int arpSrcIPv4Address = IPv4.toIPv4Address(arpPacket.getSenderProtocolAddress());
   			int arpDstIPv4Address = IPv4.toIPv4Address(arpPacket.getTargetProtocolAddress());
   			MacAddress arpSrcMacAddress = MacAddress.valueOf(arpPacket.getSenderHardwareAddress());
   			MacAddress arpDstMacAddress = MacAddress.valueOf(arpPacket.getTargetHardwareAddress());
   			short opcode = arpPacket.getOpCode();

    		//log.info("{}", arpSrcMacAddress.toString());

    		//Record the source mac and source ipv4 bind
    		if (!macToIpv4.containsKey(arpSrcMacAddress)) {
    			macToIpv4.put(arpSrcMacAddress, new Integer(arpSrcIPv4Address));
    			log.info("added");
    		}

    		//Record the source mac and source port bind
    		if (!macToDevicePort.containsKey(arpSrcMacAddress)) {
    			macToDevicePort.put(arpSrcMacAddress, new Pair<DeviceId, PortNumber>(deviceId, inputPort));
    			log.info("host {} is at port {} of {}", arpSrcMacAddress.toString(), inputPort.toString(), deviceId.toString());
    		}

    		//Action
    		if (opcode == 1) { //request

    			//Check whether the bind is exist between mac and ipv4
    			if (macToIpv4.containsKey(arpDstMacAddress)) {

    				log.info("host found at port {} of {}, generating packet", inputPort.toString(), deviceId.toString());
    				//Packet-out to the specific port with new created packet

    				TrafficTreatment treatment = DefaultTrafficTreatment.builder()
    					.setOutput(inputPort).build();

    				Ethernet replyPacket = ARP.buildArpReply(
    					Ip4Address.valueOf(macToIpv4.get(arpDstMacAddress).intValue()),
    					arpDstMacAddress,
    					ethPkt);

    				OutboundPacket outPacket = new DefaultOutboundPacket(
    					deviceId,
    					treatment,
    					ByteBuffer.wrap(replyPacket.serialize()));

    				packetService.emit(outPacket);

    			} else {

    				//Flooding the package to every edge port

    				log.info("start flooding");
    				Topology topology = topologyService.currentTopology();
    				TopologyGraph topologyGraph = topologyService.getGraph(topology);
    				Set<TopologyVertex> deviceSet = topologyGraph.getVertexes();



    				for(TopologyVertex vertex : deviceSet) {

    					Set<Link> links = linkService.getDeviceLinks(vertex.deviceId());

    					log.info("select {} to flood", vertex.toString());

    					for (ConnectPoint point : edgePortService.getEdgePoints(vertex.deviceId())) {

    						log.info("flooding {}, {}", vertex.toString(), point.port().toString());

    						TrafficTreatment treatment = DefaultTrafficTreatment.builder()
    							.setOutput(point.port()).build();

    						OutboundPacket outPacket = new DefaultOutboundPacket(
    							vertex.deviceId(),
    							treatment,
    							context.inPacket().unparsed());

    						packetService.emit(outPacket);
    						
    					}

    				}
    				log.info("end flooding");

    			}
    		}
    		else if (opcode == 2) { //reply

    			//Packet-out to the specific port
    			log.info("get reply");
    			if (macToIpv4.containsKey(arpDstMacAddress)) {

    				TrafficTreatment treatment = DefaultTrafficTreatment.builder()
    					.setOutput(macToDevicePort.get(arpDstMacAddress).getValue()).build();

    				OutboundPacket outPacket = new DefaultOutboundPacket(
    					macToDevicePort.get(arpDstMacAddress).getKey(),
    					treatment,
    					context.inPacket().unparsed());
    				packetService.emit(outPacket);
    				log.info("send to port {} of {}", macToDevicePort.get(arpDstMacAddress).getValue(), macToDevicePort.get(arpDstMacAddress).getKey());
    			}
    		}
    	}
    }
}
