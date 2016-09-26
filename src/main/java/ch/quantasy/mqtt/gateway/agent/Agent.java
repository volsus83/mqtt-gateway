/*
 * /*
 *  *   "SeMqWay"
 *  *
 *  *    SeMqWay(tm): A gateway to provide an MQTT-View for any micro-service (Service MQTT-Gateway).
 *  *
 *  *    Copyright (c) 2016 Bern University of Applied Sciences (BFH),
 *  *    Research Institute for Security in the Information Society (RISIS), Wireless Communications & Secure Internet of Things (WiCom & SIoT),
 *  *    Quellgasse 21, CH-2501 Biel, Switzerland
 *  *
 *  *    Licensed under Dual License consisting of:
 *  *    1. GNU Affero General Public License (AGPL) v3
 *  *    and
 *  *    2. Commercial license
 *  *
 *  *
 *  *    1. This program is free software: you can redistribute it and/or modify
 *  *     it under the terms of the GNU Affero General Public License as published by
 *  *     the Free Software Foundation, either version 3 of the License, or
 *  *     (at your option) any later version.
 *  *
 *  *     This program is distributed in the hope that it will be useful,
 *  *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  *     GNU Affero General Public License for more details.
 *  *
 *  *     You should have received a copy of the GNU Affero General Public License
 *  *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *  *
 *  *
 *  *    2. Licensees holding valid commercial licenses for TiMqWay may use this file in
 *  *     accordance with the commercial license agreement provided with the
 *  *     Software or, alternatively, in accordance with the terms contained in
 *  *     a written agreement between you and Bern University of Applied Sciences (BFH),
 *  *     Research Institute for Security in the Information Society (RISIS), Wireless Communications & Secure Internet of Things (WiCom & SIoT),
 *  *     Quellgasse 21, CH-2501 Biel, Switzerland.
 *  *
 *  *
 *  *     For further information contact <e-mail: reto.koenig@bfh.ch>
 *  *
 *  *
 */
package ch.quantasy.mqtt.gateway.agent;

import ch.quantasy.mqtt.communication.mqtt.MQTTCommunication;
import ch.quantasy.mqtt.communication.mqtt.MQTTCommunicationCallback;
import ch.quantasy.mqtt.communication.mqtt.MQTTParameters;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.introspect.VisibilityChecker;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.net.URI;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;

/**
 *
 * @author reto
 */
public class Agent implements MQTTCommunicationCallback {

    private final MQTTCommunication communication;
    private final ObjectMapper mapper;

    private final Map<String, MqttMessage> messageMap;
    private final Map<String, Set<MessageConsumer>> messageConsumerMap;
    private AgentContract agentContract;
    private final static ExecutorService executorService;
    private MQTTParameters parameters;
    
    static{
         //I do not know if this is a great idea... Check with load-tests!
        executorService = Executors.newCachedThreadPool();
    }

    public Agent(URI mqttURI, String sessionID, AgentContract agentContract) throws MqttException {
        this.agentContract = agentContract;
        messageMap = new HashMap<>();
        messageConsumerMap = new HashMap<>();
        mapper = new ObjectMapper(new YAMLFactory());
        mapper.setVisibility(VisibilityChecker.Std.defaultInstance().withFieldVisibility(JsonAutoDetect.Visibility.ANY)
                .withGetterVisibility(JsonAutoDetect.Visibility.NONE)
                .withSetterVisibility(JsonAutoDetect.Visibility.NONE)
                .withCreatorVisibility(JsonAutoDetect.Visibility.NONE));
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        mapper.configure(MapperFeature.PROPAGATE_TRANSIENT_MARKER, true);
        communication = new MQTTCommunication();
        parameters = new MQTTParameters();
        parameters.setClientID(sessionID);
        parameters.setIsCleanSession(true);
        parameters.setIsLastWillRetained(true);
        parameters.setLastWillQoS(1);
        parameters.setServerURIs(mqttURI);
        parameters.setWillTopic(agentContract.STATUS_CONNECTION);
        try {
            parameters.setLastWillMessage(mapper.writeValueAsBytes(Boolean.FALSE));
        } catch (JsonProcessingException ex) {
            Logger.getLogger(Agent.class.getName()).log(Level.SEVERE, null, ex);
        }
        parameters.setMqttCallback(this);
    }

    public void connect() throws MqttException {
        communication.connect(parameters);
        try {
            communication.publishActualWill(mapper.writeValueAsBytes(Boolean.TRUE));
            for (String subscription : messageConsumerMap.keySet()) {
                communication.subscribe(subscription, 1);
            }
        } catch (JsonProcessingException ex) {
            Logger.getLogger(Agent.class.getName()).log(Level.SEVERE, null, ex);
        }
    }

    public void disconnect() throws MqttException {
        try {
            communication.publishActualWill(mapper.writeValueAsBytes(Boolean.FALSE));
            for (String subscription : messageConsumerMap.keySet()) {
                communication.unsubscribe(subscription);
            }
        } catch (JsonProcessingException ex) {
            Logger.getLogger(Agent.class.getName()).log(Level.SEVERE, null, ex);
        }
        communication.disconnect();
    }

    public synchronized void subscribe(String topic, MessageConsumer consumer) {
        if (!messageConsumerMap.containsKey(topic)) {
            messageConsumerMap.put(topic, new HashSet<>());
            communication.subscribe(topic, 1);
        }
        messageConsumerMap.get(topic).add(consumer);
    }

    public synchronized void unsubscribe(String topic, MessageConsumer consumer) {
        if (messageConsumerMap.containsKey(topic)) {
            Set<MessageConsumer> messageConsumers = messageConsumerMap.get(topic);
            messageConsumers.remove(consumer);
            if (messageConsumers.isEmpty()) {
                messageConsumerMap.remove(topic);
                communication.unsubscribe(topic);
            }
        }
    }

    @Override
    public void connectionLost(Throwable thrwbl) {
        thrwbl.printStackTrace();
        System.out.println("Uuups, connection lost");
    }

    @Override
    public void deliveryComplete(IMqttDeliveryToken imdt) {
        System.out.println("done.");
    }

    @Override
    public MqttMessage getMessageToPublish(String topic) {
        return messageMap.get(topic);
    }

    public void addMessage(String topic, Object status) {
        try {
            MqttMessage message = null;
            if (status != null) {
                message = new MqttMessage(mapper.writeValueAsBytes(status));
            } else {
                message = new MqttMessage();
            }
            message.setQos(1);
            message.setRetained(true);
            topic = topic + "/" + agentContract.ID;
            messageMap.put(topic, message);
            communication.readyToPublish(this, topic);

        } catch (JsonProcessingException ex) {
            Logger.getLogger(Agent.class
                    .getName()).log(Level.SEVERE, null, ex);
        }
    }

    public ObjectMapper getMapper() {
        return mapper;
    }

    public static boolean compareTopic(final String actualTopic, final String subscribedTopic) {
        return actualTopic.matches(subscribedTopic.replaceAll("\\+", "[^/]+").replaceAll("#", ".+"));
    }

    @Override
    public void messageArrived(String topic, MqttMessage mm) {
        byte[] payload = mm.getPayload();
        if (payload == null) {
            return;
        }

        Set<MessageConsumer> messageConsumers = new HashSet<>();
        for (String subscribedTopic : this.messageConsumerMap.keySet()) {
            if (compareTopic(topic, subscribedTopic)) {
                messageConsumers.addAll(this.messageConsumerMap.get(subscribedTopic));
            }
        }
        //This way, even if a consumer has been subscribed itsself under multiple topic-filters,
        //it is only called once per topic match.
        for (MessageConsumer consumer : messageConsumers) {
            executorService.submit(new Runnable() {
                @Override
                //Not so sure if this is a great idea... Check it!
                public void run() {
                    try {
                        consumer.messageArrived(Agent.this, topic, payload);
                    } catch (Exception ex) {
                        Logger.getLogger(getClass().
                                getName()).log(Level.INFO, null, ex);
                    }
                }
            });

        }

    }
}