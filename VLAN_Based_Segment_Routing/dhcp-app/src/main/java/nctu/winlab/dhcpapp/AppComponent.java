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
package nctu.winlab.dhcpapp;

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
import org.onlab.packet.IpAddress;
import org.onlab.packet.IpPrefix;

import org.onlab.util.KryoNamespace;

//import org.onosproject.cfg.ComponentConfigService;

import org.onosproject.core.ApplicationId;
import org.onosproject.core.CoreService;

import org.onosproject.net.packet.PacketService;
import org.onosproject.net.packet.PacketProcessor;
import org.onosproject.net.packet.PacketContext;
import org.onosproject.net.packet.InboundPacket;
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

import static org.onosproject.net.config.basics.SubjectFactories.APP_SUBJECT_FACTORY;


/**
 * Skeletal ONOS application component.
 */
@Component(immediate = true)


public class AppComponent {

    private final Logger log = LoggerFactory.getLogger(getClass());

    private final InternalConfigListener cfgListener = new InternalConfigListener();

    private final Set<ConfigFactory> factories = ImmutableSet.of(
            new ConfigFactory<ApplicationId, nctu.winlab.dhcpapp.MyConfig>(APP_SUBJECT_FACTORY,
                                                                nctu.winlab.dhcpapp.MyConfig.class,
                                                                "myconfig") {
                @Override
                public nctu.winlab.dhcpapp.MyConfig createConfig() {
                    return new nctu.winlab.dhcpapp.MyConfig();
                }
            }
    );

    private EventuallyConsistentMap<Pair<DeviceId, MacAddress>, PortNumber> metrics;

    private HashMap<MacAddress, Pair<DeviceId, PortNumber>> dhcpDiscoverRecord;

    private ApplicationId appId;
    private static String myName = "of:0000000000000002/4";
    private static String myInfo = "ss";

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

	


	//@Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
	//protected TopologyGraph topologyGraph;

	//@Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
	//protected ComponentConfigService cfgService;

	private int flowPriority = 10;

	private MyProcessor processor = new MyProcessor();

	private TopologyVertex dhcpSwitch;
	private PortNumber dhcpPort;




    @Activate
    protected void activate() {
    	appId = coreService.registerApplication("nctu.winlab.dhcpapp");

    	KryoNamespace.Builder metricSerializer = KryoNamespace.newBuilder()
    			.register(KryoNamespaces.API)
    			.register(MultiValuedTimestamp.class);

    	metrics = storageService.<Pair<DeviceId, MacAddress>, PortNumber>eventuallyConsistentMapBuilder()
    			.withName("metrics-dhcp-app")
    			.withSerializer(metricSerializer)
    			.withTimestampProvider((key, metricsData) -> new
    					MultiValuedTimestamp<>(new WallClockTimestamp(), System.nanoTime()))
    			.build();

    	dhcpDiscoverRecord = new HashMap<MacAddress, Pair<DeviceId, PortNumber>>();

    	cfgService.addListener(cfgListener);

    	factories.forEach(cfgService::registerConfigFactory);

    	cfgListener.reconfigureNetwork(cfgService.getConfig(appId, nctu.winlab.dhcpapp.MyConfig.class));

    	packetService.addProcessor(processor, PacketProcessor.director(2));

    	//cfgService.registerProperties(getClass());
    	requestIntercepts();
    	
        log.info("Started");
    }

    @Deactivate
    protected void deactivate() {
    	cfgService.removeListener(cfgListener);
        factories.forEach(cfgService::unregisterConfigFactory);
        log.info("Stopped");
    }

    private void requestIntercepts() {
    	TrafficSelector.Builder selector = DefaultTrafficSelector.builder();
    	//DHCP Discover & DHCP Request
        selector.matchEthType(Ethernet.TYPE_IPV4)
                .matchIPProtocol(IPv4.PROTOCOL_UDP)
                .matchUdpSrc(TpPort.tpPort(68))
                .matchUdpDst(TpPort.tpPort(67));
    	packetService.requestPackets(selector.build(), PacketPriority.REACTIVE, appId);
        //DHCP Offer & DHCP Ack
        selector.matchEthType(Ethernet.TYPE_IPV4)
                .matchIPProtocol(IPv4.PROTOCOL_UDP)
                .matchUdpSrc(TpPort.tpPort(67))
                .matchUdpDst(TpPort.tpPort(68));
        packetService.requestPackets(selector.build(), PacketPriority.REACTIVE, appId);
    }

    private void withdrawIntercepts() {
	 	TrafficSelector.Builder selector = DefaultTrafficSelector.builder();
	 	//DHCP Discover & DHCP Request
        selector.matchEthType(Ethernet.TYPE_IPV4)
                .matchIPProtocol(IPv4.PROTOCOL_UDP)
                .matchUdpSrc(TpPort.tpPort(68))
                .matchUdpDst(TpPort.tpPort(67));
	 	packetService.cancelPackets(selector.build(), PacketPriority.REACTIVE, appId);
        //DHCP Offer & DHCP Ack
        selector.matchEthType(Ethernet.TYPE_IPV4)
                .matchIPProtocol(IPv4.PROTOCOL_UDP)
                .matchUdpSrc(TpPort.tpPort(67))
                .matchUdpDst(TpPort.tpPort(68));
        packetService.cancelPackets(selector.build(), PacketPriority.REACTIVE, appId);
    }

    private class InternalConfigListener implements NetworkConfigListener {

        /**
         * Reconfigures variable "dhcpConnectPoint" when there's new valid configuration uploaded.
         *
         * @param cfg configuration object
         */
        private void reconfigureNetwork(nctu.winlab.dhcpapp.MyConfig cfg) {
            if (cfg == null) {
                return;
            }
            if (cfg.myname() != null) {
                myName = cfg.myname();
            }
            if (cfg.myInfo() != null) {
            	myInfo = cfg.myInfo();
            }
        }
        /**
         * To handle the config event(s).
         *
         * @param event config event
         */
        @Override
        public void event(NetworkConfigEvent event) {

            // While configuration is uploaded, update the variable "myName".
            if ((event.type() == NetworkConfigEvent.Type.CONFIG_ADDED ||
                    event.type() == NetworkConfigEvent.Type.CONFIG_UPDATED) &&
                    event.configClass().equals(nctu.winlab.dhcpapp.MyConfig.class)) {

                nctu.winlab.dhcpapp.MyConfig cfg = cfgService.getConfig(appId, nctu.winlab.dhcpapp.MyConfig.class);
                reconfigureNetwork(cfg);
                log.info("Location is under {} and {}", myName.substring(0, 19), myName.substring(20, 21));
                log.info("message: {}", myInfo);
            }
        }
    }

    Set<TopologyVertex> vertexes;

    private class MyProcessor implements PacketProcessor {

    	private ArrayList<TopologyVertex> shortestPath = new ArrayList<TopologyVertex>();

    	public ArrayList<TopologyVertex> BFS(TopologyGraph graph, TopologyVertex source, TopologyVertex destination) {
    		
    		shortestPath.clear();

    		ArrayList<TopologyVertex> path = new ArrayList<TopologyVertex>();

    		if (source.equals(destination) && vertexes.contains(source)) {
    			path.add(source);
    			return path;
    		}

    		ArrayDeque<TopologyVertex> queue = new ArrayDeque<TopologyVertex>();

    		ArrayDeque<TopologyVertex> visited = new ArrayDeque<TopologyVertex>();

    		queue.offer(source);
    		while(!queue.isEmpty()) {
    			TopologyVertex vertex = queue.poll();
    			visited.offer(vertex);

    			ArrayList<TopologyVertex> neighboursList = new ArrayList<TopologyVertex>();
    			for(TopologyEdge edge : graph.getEdgesFrom(vertex)) {
    				neighboursList.add(edge.dst());
    			}

    			int index = 0;
    			int neighboursSize = neighboursList.size();
    			while(index != neighboursSize) {
    				TopologyVertex neighbour = neighboursList.get(index);

    				path.add(neighbour);
    				path.add(vertex);

    				if(neighbour.equals(destination)) {
    					return processPath(source, destination, path);
    				} else {
    					if(!visited.contains(neighbour)) {
    						queue.offer(neighbour);
    					}
    				}
    				index++;
    			}
    		}
    		return null;
    	}

    	private ArrayList<TopologyVertex> processPath(TopologyVertex src, TopologyVertex destination, ArrayList<TopologyVertex> path) {
    		
    		int index = path.indexOf(destination);
    		TopologyVertex source = path.get(index + 1);

    		shortestPath.add(0, destination);

    		if(source.equals(src)) {

    			shortestPath.add(0, src);
    			return shortestPath;
    		} else {

    			return processPath(src, source, path);
    		}
    	}

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
			IPv4 ipv4Packet = (IPv4) ethPkt.getPayload();
    		int srcIPv4Address = ipv4Packet.getSourceAddress();
    		int dstIPv4Address = ipv4Packet.getDestinationAddress();

    		Topology topology = topologyService.currentTopology();
    		TopologyGraph topologyGraph = topologyService.getGraph(topology);

    		TopologyVertex srcSwitch;
    		vertexes = topologyGraph.getVertexes();

    		//get all hosts connected to each switches
    		HashMap<Host, TopologyVertex> hosts = new HashMap<Host, TopologyVertex>();
    		for(TopologyVertex vertex : vertexes) {
    			for(Host host : hostService.getConnectedHosts(vertex.deviceId())) {
    				hosts.put(host, vertex);
    			}
    		}

    		srcSwitch = hosts.get((Host)hostService.getHostsByMac(ethPkt.getSourceMAC()).toArray()[0]);

    		TopologyVertex dstSwitch = srcSwitch;
    		
    		for (TopologyVertex vertex : vertexes) {

    			if (vertex.deviceId().toString().equals(myName.substring(0, 19))) {
    				dstSwitch = vertex;
    				log.info("the switch with dhcp host is {}", dstSwitch.toString());
    				break;
    			}
    		}
    		
    		log.info("srcSwitch and DstSwitch are {} and {}", srcSwitch.toString(), dstSwitch.toString());

    		ArrayList<TopologyVertex> vertexPathForward = BFS(topologyGraph, srcSwitch, dstSwitch);

    		TopologyVertex vertexSrc = null, vertexDst = null;
    		ArrayList<Pair<TopologyVertex, PortNumber>> vertexEgressPortForward = new ArrayList<Pair<TopologyVertex, PortNumber>>();
    		for(TopologyVertex vertex : vertexPathForward) {
    			
    			if(vertexDst != null) {
    				vertexSrc = vertexDst;
    				vertexDst = vertex;
    				for(Link link : linkService.getDeviceEgressLinks(vertexSrc.deviceId())) {
    					if(link.dst().deviceId().equals(vertexDst.deviceId())) {

    						//add the port to the list
    						vertexEgressPortForward.add(new Pair<TopologyVertex, PortNumber>(vertexSrc, link.src().port()));
    						//log.info("path: {}, EgressPort: {}", vertexSrc.deviceId().toString(), link.src().port());
    						break;
    					}
    				}
    			} else {
    				vertexDst = vertex;
    			}
    		}
    		vertexEgressPortForward.add(new Pair<TopologyVertex, PortNumber>(vertexDst, PortNumber.portNumber(myName.substring(20, 21))));

    		log.info("Forward Path: ++++++++++++++++++");
    		for(Pair<TopologyVertex, PortNumber> node : vertexEgressPortForward) {
    			log.info("vertex: {}, EgressPort: {}", node.getKey(), node.getValue());
    		}

    		for(int i = vertexEgressPortForward.size() - 1; i >= 0; i--) {

    			TrafficSelector.Builder selectorBuilder = DefaultTrafficSelector.builder();
    			selectorBuilder
    					.matchEthType((short)0x0800)
    					.matchIPDst(IpPrefix.valueOf(IpAddress.valueOf("255.255.255.255"), 32))
                        .matchIPProtocol(IPv4.PROTOCOL_UDP)
                        .matchUdpSrc(TpPort.tpPort(68))
                        .matchUdpDst(TpPort.tpPort(67));
    			TrafficTreatment treatment = DefaultTrafficTreatment.builder()
    					.setOutput(vertexEgressPortForward.get(i).getValue())
    					.build();
    			ForwardingObjective forwardingObjective = DefaultForwardingObjective.builder()
    					.withSelector(selectorBuilder.build())
    					.withTreatment(treatment)
    					.withPriority(20)
    					.withFlag(ForwardingObjective.Flag.VERSATILE)
    					.fromApp(appId)
    					.makeTemporary(20)
    					.add();
    			flowObjectiveService.forward(vertexEgressPortForward.get(i).getKey().deviceId(), forwardingObjective);
    		}

    		Host srcHost = (Host)hostService.getHostsByMac(ethPkt.getSourceMAC()).toArray()[0];

    		ArrayList<TopologyVertex> vertexPathBackward = BFS(topologyGraph, dstSwitch, srcSwitch);

    		ArrayList<Pair<TopologyVertex, PortNumber>> vertexEgressPortBackward = new ArrayList<Pair<TopologyVertex, PortNumber>>();

    		vertexDst = null;

    		for(TopologyVertex vertex : vertexPathBackward) {
    			
    			if(vertexDst != null) {
    				vertexSrc = vertexDst;
    				vertexDst = vertex;
    				for(Link link : linkService.getDeviceEgressLinks(vertexSrc.deviceId())) {
    					if(link.dst().deviceId().equals(vertexDst.deviceId())) {
    						vertexEgressPortBackward.add(new Pair<TopologyVertex, PortNumber>(vertexSrc, link.src().port()));
    						//log.info("path: {}, EgressPort: {}", vertexSrc.deviceId().toString(), link.src().port());
    						break;
    					}
    				}


    			} else {
    				vertexDst = vertex;
    			}
    		}

    		vertexEgressPortBackward.add(new Pair<TopologyVertex, PortNumber>(vertexDst, srcHost.location().port()));

    		log.info("Backward Path: ++++++++++++++++++");
    		for(Pair<TopologyVertex, PortNumber> node : vertexEgressPortBackward) {
    			log.info("vertex: {}, EgressPort: {}", node.getKey(), node.getValue());
    		}
    		
    		for(int i = vertexEgressPortBackward.size() - 1; i >= 0; i--) {

    			TrafficSelector.Builder selectorBuilder = DefaultTrafficSelector.builder();
    			selectorBuilder
                        .matchEthDst(srcMacAddress)
                        .matchEthType(Ethernet.TYPE_IPV4)
                        .matchIPProtocol(IPv4.PROTOCOL_UDP)
                        .matchUdpSrc(TpPort.tpPort(67))
                        .matchUdpDst(TpPort.tpPort(68));
    			TrafficTreatment treatment = DefaultTrafficTreatment.builder()
    					.setOutput(vertexEgressPortBackward.get(i).getValue())
    					.build();
    			ForwardingObjective forwardingObjective = DefaultForwardingObjective.builder()
    					.withSelector(selectorBuilder.build())
    					.withTreatment(treatment)
    					.withPriority(20)
    					.withFlag(ForwardingObjective.Flag.VERSATILE)
    					.fromApp(appId)
    					.makeTemporary(20)
    					.add();
    			flowObjectiveService.forward(vertexEgressPortBackward.get(i).getKey().deviceId(), forwardingObjective);
    		}
    		context.treatmentBuilder().setOutput(vertexEgressPortForward.get(0).getValue());
    	}
    }
}
