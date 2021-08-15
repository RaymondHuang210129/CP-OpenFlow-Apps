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

import org.onosproject.net.flow.TrafficSelector;
import org.onosproject.net.flow.TrafficTreatment;
import org.onosproject.net.flow.DefaultTrafficSelector;
import org.onosproject.net.flow.DefaultTrafficTreatment;

import org.onosproject.net.flowobjective.ForwardingObjective;
import org.onosproject.net.flowobjective.DefaultForwardingObjective;
import org.onosproject.net.flowobjective.FlowObjectiveService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.onosproject.store.serializers.KryoNamespaces;

import org.onosproject.store.service.EventuallyConsistentMap;
import org.onosproject.store.service.StorageService;
import org.onosproject.store.service.WallClockTimestamp;
import org.onosproject.store.service.MultiValuedTimestamp;

import javafx.util.Pair;



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

    

    private class MyProcessor implements PacketProcessor {

    	@Override
    	public void process(PacketContext context) {
    		

    		if (context.isHandled()) {
    			return;
    		}
    		
    		InboundPacket pkt = context.inPacket();
    		Ethernet ethPkt = pkt.parsed();

    		log.info("received packet, Ethernet type {}", ethPkt.getEtherType());


    		if (ethPkt == null) {
    			return;
    		}

    		
    		
    		//get the packet's srcMac and inPortNumber
    		MacAddress srcMacAddress = ethPkt.getSourceMAC();
    		MacAddress dstMacAddress = ethPkt.getDestinationMAC();
    		PortNumber inPortNumber = context.inPacket().receivedFrom().port();
    		//PortNumber outPortNumber = PortNumber.FLOOD;
    		DeviceId deviceId = context.inPacket().receivedFrom().deviceId();
    		
    		Pair<DeviceId, MacAddress> srcPacket = new Pair<>(deviceId, srcMacAddress);
    		Pair<DeviceId, MacAddress> dstPacket = new Pair<>(deviceId, dstMacAddress);
    		log.info("packet from {}", inPortNumber);

    		if (ethPkt.getEtherType() == Ethernet.TYPE_IPV4) {
    			log.info("ipv4 packet!!!!!!!!!");
    		}

    		

    		//if the mapping record does not exist
    		if (!metrics.containsKey(srcPacket)) {

    			//add the record to table
    			metrics.put(srcPacket, inPortNumber);
    		} else {

    			//if the mapping record exists but in port is different
    			if (!metrics.get(srcPacket).equals(inPortNumber)) {

    				//update the table
    				metrics.put(srcPacket, inPortNumber);
    			}
    		}
    		//find whether the dest mac is on the table
    		if (metrics.containsKey(dstPacket)) {

    			//assign the out port
    			log.info("send to {}", metrics.get(dstPacket));
    			context.treatmentBuilder().setOutput(metrics.get(dstPacket));
    			context.send();

    			TrafficSelector.Builder selectorBuilder = DefaultTrafficSelector.builder();
    			selectorBuilder.matchEthDst(dstMacAddress);
    			TrafficTreatment treatment = DefaultTrafficTreatment.builder()
    					.setOutput(metrics.get(dstPacket))
    					.build();
    			ForwardingObjective forwardingObjective = DefaultForwardingObjective.builder()
    					.withSelector(selectorBuilder.build())
    					.withTreatment(treatment)
    					.withPriority(flowPriority)
    					.withFlag(ForwardingObjective.Flag.VERSATILE)
    					.fromApp(appId)
    					.add();
    			flowObjectiveService.forward(deviceId, forwardingObjective);

    		} else {

    			//set to null and flood the packet later
    			log.info("send to flood");
    			context.treatmentBuilder().setOutput(PortNumber.FLOOD);
    			context.send();
    			log.info("6-2");
    		}
			log.info("5");

    		return;
    	}
    }
}
