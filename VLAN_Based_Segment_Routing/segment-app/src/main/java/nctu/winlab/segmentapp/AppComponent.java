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
package nctu.winlab.segmentapp;

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
import org.onlab.packet.VlanId;

import org.onlab.util.KryoNamespace;

//import org.onosproject.cfg.ComponentConfigService;

import org.onosproject.core.ApplicationId;
import org.onosproject.core.GroupId;
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

import org.onosproject.net.group.GroupDescription;
import org.onosproject.net.group.DefaultGroupDescription;
import org.onosproject.net.group.GroupService;
import org.onosproject.net.group.DefaultGroupKey;
import org.onosproject.net.group.GroupBucket;
import org.onosproject.net.group.GroupBuckets;
import org.onosproject.net.group.DefaultGroupBucket;

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

import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.List;
import java.util.HashSet;

import com.google.common.collect.ImmutableSet;

import java.util.Arrays;
import java.util.regex.Pattern;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.ListIterator;

import com.fasterxml.jackson.databind.JsonNode;

import static org.onosproject.net.config.basics.SubjectFactories.APP_SUBJECT_FACTORY;


/**
 * Skeletal ONOS application component.
 */
@Component(immediate = true)


public class AppComponent {

    private final Logger log = LoggerFactory.getLogger(getClass());

    private final InternalConfigListener cfgListener = new InternalConfigListener();

    private final Set<ConfigFactory> factories = ImmutableSet.of(
            new ConfigFactory<ApplicationId, nctu.winlab.segmentapp.MyConfig>(APP_SUBJECT_FACTORY,
                                                                nctu.winlab.segmentapp.MyConfig.class,
                                                                "myconfig") {
                @Override
                public nctu.winlab.segmentapp.MyConfig createConfig() {
                    return new nctu.winlab.segmentapp.MyConfig();
                }
            }
    );

    private EventuallyConsistentMap<Pair<DeviceId, MacAddress>, PortNumber> metrics;

    private HashMap<MacAddress, Pair<DeviceId, PortNumber>> dhcpDiscoverRecord;

    private ApplicationId appId;
    private static String dhcpLocation = "";
    private static String[] environmentConfig = null;

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
    protected GroupService groupService;

	


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
    	
    	appId = coreService.registerApplication("nctu.winlab.segmentapp");

    	KryoNamespace.Builder metricSerializer = KryoNamespace.newBuilder()
    			.register(KryoNamespaces.API)
    			.register(MultiValuedTimestamp.class);

    	metrics = storageService.<Pair<DeviceId, MacAddress>, PortNumber>eventuallyConsistentMapBuilder()
    			.withName("metrics-segment-app")
    			.withSerializer(metricSerializer)
    			.withTimestampProvider((key, metricsData) -> new
    					MultiValuedTimestamp<>(new WallClockTimestamp(), System.nanoTime()))
    			.build();

    	dhcpDiscoverRecord = new HashMap<MacAddress, Pair<DeviceId, PortNumber>>();

    	cfgService.addListener(cfgListener);

    	factories.forEach(cfgService::registerConfigFactory);

    	cfgListener.reconfigureNetwork(cfgService.getConfig(appId, nctu.winlab.segmentapp.MyConfig.class));

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

    }

    private void withdrawIntercepts() {

    }

    private class InternalConfigListener implements NetworkConfigListener {

        /**
         * Reconfigures variable "dhcpConnectPoint" when there's new valid configuration uploaded.
         *
         * @param cfg configuration object
         */
        private void reconfigureNetwork(nctu.winlab.segmentapp.MyConfig cfg) {
            if (cfg == null) {
                return;
            }
            if (cfg.getConfig() != null) {
                log.info("config string: {}", cfg.getConfig());
                environmentConfig = cfg.getConfig().split(",");
            }
        }
        /**
         * To handle the config event(s).
         *
         * @param event config event
         */
        @Override
        public void event(NetworkConfigEvent event) {

        	//create switch list to find switch with id
            
        	Topology topology = topologyService.currentTopology();
        	TopologyGraph topologyGraph = topologyService.getGraph(topology);
        	HashMap<String, TopologyVertex> switchList = new HashMap<String, TopologyVertex>();
            log.info("switchList creating");
        	for(TopologyVertex vertex : topologyGraph.getVertexes()) {
        		switchList.put(vertex.deviceId().toString(), vertex);
                log.info("----{}", vertex.deviceId().toString());
        	}


            // While configuration is uploaded, update the variable "myName".
            if ((event.type() == NetworkConfigEvent.Type.CONFIG_ADDED ||
                    event.type() == NetworkConfigEvent.Type.CONFIG_UPDATED) &&
                    event.configClass().equals(nctu.winlab.segmentapp.MyConfig.class)) {
                log.info("start configuration process");
                nctu.winlab.segmentapp.MyConfig cfg = cfgService.getConfig(appId, nctu.winlab.segmentapp.MyConfig.class);
                reconfigureNetwork(cfg);


                //dhcpLocation = environmentConfig.get("dhcpLocation").asText();

                int groupId = 1; //assign groupId to each pair of src/dst, will be incremented

                for(String srcSwitchConfig : environmentConfig) {
                    log.info("srcSwitchConfig: {}", srcSwitchConfig);
                	if (srcSwitchConfig.substring(24).equals("None")) {continue;}

                	//install rules for intra subnet
                	TopologyVertex intraSwitch = switchList.get(srcSwitchConfig.substring(0, 19));

                	for(Host intraHost : hostService.getConnectedHosts(DeviceId.deviceId(srcSwitchConfig.substring(0, 19)))) {
                        log.info("---- add rule for intra subnet to port {}", intraHost.location().port().toString());
                		TrafficSelector.Builder selectorBuilder = DefaultTrafficSelector.builder()
                																		.matchEthType(Ethernet.TYPE_IPV4)
                																		.matchIPDst(IpPrefix.valueOf((IpAddress)intraHost.ipAddresses().toArray()[0], 32));
                		TrafficTreatment.Builder treatmentBuilder = DefaultTrafficTreatment.builder()
                																		   .setOutput(intraHost.location().port());
                		ForwardingObjective forwardingObjective = DefaultForwardingObjective.builder()
                																			.withSelector(selectorBuilder.build())
                																			.withTreatment(treatmentBuilder.build())
                																			.withPriority(flowPriority)
                																			.withFlag(ForwardingObjective.Flag.VERSATILE)
    																						.fromApp(appId)
    																						//.makeTemporary(180)
    																						.add();	
    					flowObjectiveService.forward(intraSwitch.deviceId(), forwardingObjective);	
                	}

                	//install rules for popping VLAN and route
                	for(Host intraHost : hostService.getConnectedHosts(DeviceId.deviceId(srcSwitchConfig.substring(0, 19)))) {
                        log.info("---- add rule for pop VLAN to port {}", intraHost.location().port().toString());
                		TrafficSelector.Builder selectorBuilder = DefaultTrafficSelector.builder()
                																		.matchEthType(Ethernet.TYPE_IPV4)
                																		.matchVlanId(VlanId.vlanId(srcSwitchConfig.substring(20, 23)))
                																		.matchIPDst(IpPrefix.valueOf((IpAddress)intraHost.ipAddresses().toArray()[0], 32));
                		TrafficTreatment.Builder treatmentBuilder = DefaultTrafficTreatment.builder()
                																		   .popVlan()
                																		   .setOutput(intraHost.location().port());
                		ForwardingObjective forwardingObjective = DefaultForwardingObjective.builder()
                																			.withSelector(selectorBuilder.build())
                																			.withTreatment(treatmentBuilder.build())
                																			.withPriority(flowPriority + 1)
                																			.withFlag(ForwardingObjective.Flag.VERSATILE)
    																						.fromApp(appId)
    																						//.makeTemporary(180)
    																						.add();	
    					flowObjectiveService.forward(intraSwitch.deviceId(), forwardingObjective);
                	}


                	for(String destSwitchConfig : environmentConfig) {
                        log.info("srcSwitchConfig: {}", destSwitchConfig);
                		if (destSwitchConfig.substring(24).equals("None")) {continue;}

                		if (!srcSwitchConfig.equals(destSwitchConfig)) { //rules for tag and untag

                			//create rule List (without destination switch)
                			HashMap<TopologyVertex, HashSet<PortNumber>> rules = new HashMap<TopologyVertex, HashSet<PortNumber>>();
                            log.info("test {}", srcSwitchConfig.substring(0, 19));
                			TopologyVertex source = switchList.get(srcSwitchConfig.substring(0, 19));
                			TopologyVertex destination = switchList.get(destSwitchConfig.substring(0, 19));
                			HashSet<ArrayList<TopologyVertex>> paths = shortestPaths(topologyGraph, 
                															source, 
                															destination);
            				for (ArrayList<TopologyVertex> path : paths) {
            					for (int i = 1; i < path.size(); i++) {
            						PortNumber port = findOutputPort(topologyGraph, path.get(i - 1), path.get(i));
            						if (!rules.containsKey(path.get(i - 1))) {
            							HashSet<PortNumber> set = new HashSet<PortNumber>();
            							set.add(port);
            							rules.put(path.get(i - 1), set);
            						} else {
            							HashSet<PortNumber> set = rules.get(path.get(i - 1));
            							set.add(port);
            							rules.replace(path.get(i - 1), set);
            						}
            					}
            				}

            				// install groups and rules for each switch in the paths
            				for (Map.Entry<TopologyVertex, HashSet<PortNumber>> entry : rules.entrySet()) {

	            				//groups
	            				ArrayList<GroupBucket> outBuckets = new ArrayList<GroupBucket>();
	            				for (PortNumber port : entry.getValue()) {
	            					TrafficTreatment.Builder treatment = DefaultTrafficTreatment.builder()
	            																				.setOutput(port);
	            					if (entry.getKey().equals(source)) { //push vlan if switch is source
	            						treatment.pushVlan().setVlanId(VlanId.vlanId(destSwitchConfig.substring(20, 23)));
	            					}
	            					outBuckets.add(DefaultGroupBucket.createSelectGroupBucket(treatment.build()));
	            				}
	            				String groupKey = Integer.toString(groupId).concat(entry.getKey().deviceId().toString());
	            				GroupDescription groupDescription = new DefaultGroupDescription(entry.getKey().deviceId(),
	            																				GroupDescription.Type.SELECT,
	            																				new GroupBuckets(outBuckets),
	            																				new DefaultGroupKey(groupKey.getBytes()),
	            																				new Integer(groupId),
	            																				appId);
	            				groupService.addGroup(groupDescription);

	            				//rules
	            				TrafficSelector.Builder selectorBuilder = DefaultTrafficSelector.builder();
    							if (entry.getKey().equals(source)) { //match dstAddr if is source, else match vlan
    								selectorBuilder.matchEthType(Ethernet.TYPE_IPV4)
    											   .matchIPDst(IpPrefix.valueOf(destSwitchConfig.substring(24)));
    							} else {
    								selectorBuilder.matchEthType(Ethernet.TYPE_IPV4)
    											   .matchVlanId(VlanId.vlanId(destSwitchConfig.substring(20, 23)));
    							}
    							TrafficTreatment treatment = DefaultTrafficTreatment.builder()
    								.group(new GroupId(groupId))
    								.build();
    							ForwardingObjective forwardingObjective = DefaultForwardingObjective.builder()
    								.withSelector(selectorBuilder.build())
    								.withTreatment(treatment)
    								.withPriority(flowPriority)
    								.withFlag(ForwardingObjective.Flag.VERSATILE)
    								.fromApp(appId)
    								//.makeTemporary(180)
    								.add();		
    							flowObjectiveService.forward(entry.getKey().deviceId(), forwardingObjective);
            				}
                		} 
                		groupId++;
                	}
                }
            }
            return;
        }

        private PortNumber findOutputPort(TopologyGraph graph, TopologyVertex source, TopologyVertex destination) {
        	for (TopologyEdge edge : graph.getEdgesFrom(source)) {
        		if (edge.dst().equals(destination)) {
        			return edge.link().src().port();
        		}
        	}
        	return null;
        }

        private HashSet<ArrayList<TopologyVertex>> shortestPaths(TopologyGraph graph, TopologyVertex source, TopologyVertex destination) {

        	HashSet<ArrayList<TopologyVertex>> paths = new HashSet<ArrayList<TopologyVertex>>();
            ArrayList tempList = new ArrayList();
            tempList.add(source);
        	paths.add(tempList);

        	for(int i = 0; i < 20; i++) {

        		boolean isReached = false;
        		//backup current path
        		HashSet<ArrayList<TopologyVertex>> temp = (HashSet<ArrayList<TopologyVertex>>)paths.clone();
                HashSet<ArrayList<TopologyVertex>> temp3 = (HashSet<ArrayList<TopologyVertex>>)paths.clone();
        		//append each path
        		for(ArrayList<TopologyVertex> currentPath : temp3) {
        			TopologyVertex currentNode = currentPath.get(i);

        			//find all neighbor nodes
        			for(TopologyEdge edge : graph.getEdgesFrom(currentNode)) {

        				//append the path if not traversed
        				if (!currentPath.contains(edge.dst())) {
        					ArrayList<TopologyVertex> temp2 = (ArrayList<TopologyVertex>)currentPath.clone();
        					temp2.add(edge.dst());
        					paths.add(temp2);

        					//mark if reach destination
        					if (edge.dst().equals(destination)) {
        						isReached = true;
        					}
        				}
    				}
        		}

        		//remove original path
        		for(ArrayList<TopologyVertex> currentPath : temp) {
        			paths.remove(currentPath);
        		}

        		//check reach destination or not
        		if (isReached) {
        			HashSet<ArrayList<TopologyVertex>> result = new HashSet<ArrayList<TopologyVertex>>();
        			for (ArrayList<TopologyVertex> currentPath : paths) {
        				if (currentPath.get(i + 1).equals(destination)) {
        					result.add(currentPath);
        				}
        			}
        			return result;
        		}

        		//check if cannot reach
        		if (paths.isEmpty()) {
        			return null;
        		}
        	}
        	return null;
        }
 

    }

    HashSet<TopologyVertex> vertexes;

    private class MyProcessor implements PacketProcessor {

    	private ArrayList<TopologyVertex> shortestPath = new ArrayList<TopologyVertex>();


    	@Override
    	public void process(PacketContext context) {
    		
    		//ignore other control packet
    		if (context.isHandled()) {
    			return;
    		}
    	}
    }
}
