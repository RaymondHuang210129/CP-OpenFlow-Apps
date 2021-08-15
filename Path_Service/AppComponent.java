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
package nctu.winlab.testapp;

import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Deactivate;
import org.apache.felix.scr.annotations.Property;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.ReferenceCardinality;
import org.apache.felix.scr.annotations.Service;

import org.onlab.packet.Ethernet;
import org.onlab.packet.MacAddress;

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

import javafx.util.Pair;

import java.util.HashMap;
import java.util.Set;

import com.google.common.collect.ImmutableSet;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.ListIterator;






/**
 * Skeletal ONOS application component.
 */
@Component(immediate = true)
@Service(value = AppComponent.class)
public class AppComponent {

    private final Logger log = LoggerFactory.getLogger(getClass());

    private EventuallyConsistentMap<Pair<DeviceId, MacAddress>, PortNumber> metrics;

    private ApplicationId appId;

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

    @Activate
    protected void activate() {

    	appId = coreService.registerApplication("nctu.winlab.testapp");

    	KryoNamespace.Builder metricSerializer = KryoNamespace.newBuilder()
    			.register(KryoNamespaces.API)
    			.register(MultiValuedTimestamp.class);

    	metrics = storageService.<Pair<DeviceId, MacAddress>, PortNumber>eventuallyConsistentMapBuilder()
    			.withName("metrics-test-app")
    			.withSerializer(metricSerializer)
    			.withTimestampProvider((key, metricsData) -> new
    					MultiValuedTimestamp<>(new WallClockTimestamp(), System.nanoTime()))
    			.build();

    	packetService.addProcessor(processor, PacketProcessor.director(2));

    	//cfgService.registerProperties(getClass());
    	requestIntercepts();
    	

        log.info("Started");
    }

    @Deactivate
    protected void deactivate() {
    	packetService.removeProcessor(processor);
    	processor = null;
    	withdrawIntercepts();
        log.info("Stopped");
    }

    private void requestIntercepts() {
    	TrafficSelector.Builder selector = DefaultTrafficSelector.builder();
    	selector.matchEthType(Ethernet.TYPE_IPV4);
    	packetService.requestPackets(selector.build(), PacketPriority.REACTIVE, appId);
    }

    private void withdrawIntercepts() {
	 	TrafficSelector.Builder selector = DefaultTrafficSelector.builder();
	 	selector.matchEthType(Ethernet.TYPE_IPV4);
	 	packetService.cancelPackets(selector.build(), PacketPriority.REACTIVE, appId);
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

    		for(int i = 0; i < 100000000; i++){}


    		Topology topology = topologyService.currentTopology();
    		TopologyGraph topologyGraph = topologyService.getGraph(topology);

    		//get all switches
    		vertexes = topologyGraph.getVertexes();

    		//get all connection between switches
    		Set<TopologyEdge> edges = topologyGraph.getEdges();

    		//get all hosts connected to each switches
    		HashMap<Host, TopologyVertex> hosts = new HashMap<Host, TopologyVertex>();

    		log.info("k");
    		for(TopologyVertex vertex : vertexes) {
    			
    			log.info("{}", vertex.toString());
    			for(Host host : hostService.getConnectedHosts(vertex.deviceId())) {
    				hosts.put(host, vertex);
    				log.info("Host: {}, Switch: {}", host.id().toString(), vertex.deviceId().toString());
    			}
    		}

    		//fetch the src switch and dst switch
    		InboundPacket pkt = context.inPacket();
    		Ethernet ethPkt = pkt.parsed();
    		MacAddress srcMacAddress = ethPkt.getSourceMAC();
    		MacAddress dstMacAddress = ethPkt.getDestinationMAC();
    		if (ethPkt.getEtherType() == Ethernet.TYPE_IPV4) {
    			log.info("ipv4 packet!!!!!!!!!");
    		}

    		Host srcHost = (Host)hostService.getHostsByMac(ethPkt.getSourceMAC()).toArray()[0];
    		Host dstHost = (Host)hostService.getHostsByMac(ethPkt.getDestinationMAC()).toArray()[0];

    		TopologyVertex srcSwitch = hosts.get(srcHost);
    		TopologyVertex dstSwitch = hosts.get(dstHost);

    		//get the path by BFS
    		ArrayList<TopologyVertex> vertexPath = BFS(topologyGraph, srcSwitch, dstSwitch);

    		//make a switch-egressPort list
    		TopologyVertex vertexSrc = null, vertexDst = null;
    		ArrayList<Pair<TopologyVertex, PortNumber>> vertexEgressPort = new ArrayList<Pair<TopologyVertex, PortNumber>>();

    		for(TopologyVertex vertex : BFS(topologyGraph, srcSwitch, dstSwitch)) {
    			
    			if(vertexDst != null) {
    				vertexSrc = vertexDst;
    				vertexDst = vertex;
    				for(Link link : linkService.getDeviceEgressLinks(vertexSrc.deviceId())) {
    					if(link.dst().deviceId().equals(vertexDst.deviceId())) {
    						vertexEgressPort.add(new Pair<TopologyVertex, PortNumber>(vertexSrc, link.src().port()));
    						log.info("path: {}, EgressPort: {}", vertexSrc.deviceId().toString(), link.src().port());
    						break;
    					}
    				}


    			} else {
    				vertexDst = vertex;
    			}
    		}
    		vertexEgressPort.add(new Pair<TopologyVertex, PortNumber>(vertexDst, dstHost.location().port()));
    		log.info("last switch port: {}", dstHost.location().port().toString());

    		for(int i = vertexEgressPort.size() - 1; i >= 0; i--) {
    		//for(ListIterator it = vertexEgressPort.listIterator(vertexEgressPort.size()); it.hasPrevious(); it = it.previous()) {

    			TrafficSelector.Builder selectorBuilder = DefaultTrafficSelector.builder();
    			selectorBuilder.matchEthDst(dstMacAddress);
    			TrafficTreatment treatment = DefaultTrafficTreatment.builder()
    					.setOutput(vertexEgressPort.get(i).getValue())
    					.build();
    			ForwardingObjective forwardingObjective = DefaultForwardingObjective.builder()
    					.withSelector(selectorBuilder.build())
    					.withTreatment(treatment)
    					.withPriority(flowPriority)
    					.withFlag(ForwardingObjective.Flag.VERSATILE)
    					.fromApp(appId)
    					.add();
    			flowObjectiveService.forward(vertexEgressPort.get(i).getKey().deviceId(), forwardingObjective);
    		}


    		return;
    	}




    }
}
