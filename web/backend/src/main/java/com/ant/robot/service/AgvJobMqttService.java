package com.ant.robot.service;

import jakarta.annotation.PreDestroy;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;

@Service
public class AgvJobMqttService {
    private static final Logger log = LoggerFactory.getLogger(AgvJobMqttService.class);

    private MqttClient mqttClient;
    private final String brokerUrl = "ssl://m601c33a.ala.cn-hangzhou.emqxsl.cn:8883";
    private final String clientId = "agv001";
    private final String username = "agv001";
    private final String password = "yKDEfrz7aduVWvH";

    // 主题定义
    private final String jobTopic = "/agv/job/" + clientId;
    private final String jobReplyTopic = "/agv/job_reply/" + clientId;
    private final String statusTopic = "/agv/status/" + clientId;

    @Autowired
    private ModbusTcpClientService modbusTcpClientService;

    /**
     * 初始化MQTT连接(带SSL/TLS加密)
     */
    public void initializeConnection() throws MqttException {
        if (mqttClient != null && mqttClient.isConnected()) {
            return;
        }

        try {
            mqttClient = new MqttClient(brokerUrl, clientId, new MemoryPersistence());

            MqttConnectOptions options = new MqttConnectOptions();
            options.setUserName(username);
            options.setPassword(password.toCharArray());
            options.setAutomaticReconnect(true);
            options.setCleanSession(true);
            options.setKeepAliveInterval(30);

            // 配置SSL/TLS
            options.setSocketFactory(getSocketFactory());

            mqttClient.setCallback(new MqttCallbackExtended() {
                @Override
                public void connectComplete(boolean reconnect, String serverURI) {
                    log.info("MQTT {}成功: {}", reconnect ? "重连" : "连接", serverURI);
                }

                @Override
                public void connectionLost(Throwable cause) {
                    log.info("MQTT连接丢失: {}", cause.getMessage());
                }

                @Override
                public void messageArrived(String topic, MqttMessage message) {
                    String payload = new String(message.getPayload());
                    log.info("收到消息 [{}]: {}", topic, payload);

                    if (jobTopic.equals(topic)) {
                        handleJobMessage(payload);
                    } else if (jobReplyTopic.equals(topic)) {
                        log.info("收到作业回复: {}", payload);
                    } else if (statusTopic.equals(topic)) {
                        log.info("收到状态信息: {}", payload);
                    }
                }

                @Override
                public void deliveryComplete(IMqttDeliveryToken token) {
                    log.debug("消息发布完成: {}", token.getMessageId());
                }
            });

            mqttClient.connect(options);

            // 订阅所有相关主题，QoS设为1
            mqttClient.subscribe(jobTopic, 1);
            mqttClient.subscribe(jobReplyTopic, 1);
            mqttClient.subscribe(statusTopic, 1);

            log.info("MQTT客户端已通过SSL连接并订阅主题");
        } catch (Exception e) {
            log.error("MQTT连接失败: {}", e.getMessage());
            throw new MqttException(e);
        }
    }

    /**
     * 处理作业消息
     */
    private void handleJobMessage(String payload) {
        log.info("开始处理作业指令: {}", payload);

        try {
            // 确保Modbus连接正常
            if (ModbusTcpClientService.connectionStatus.get() != 2) {
                log.warn("Modbus连接未就绪，无法执行作业");
                return;
            }

            // 获取优先级锁
            ModbusTcpClientService.acquirePriorityLock(ModbusTcpClientService.PATH_PRIORITY);
            try {
                // 写入保持寄存器 45301 值为 1
                int registerAddress = 45301;
                int valueToWrite = 1;
                int[] values = {valueToWrite};

                boolean success = ModbusTcpClientService.writeMultipleRegisters(registerAddress, values);

                if (success) {
                    log.info("成功写入保持寄存器 {} 值为 {}", registerAddress, valueToWrite);

                    // 发送成功回复
                    String replyMsg = String.format("{\"jobId\":\"%s\", \"status\":\"success\", \"message\":\"Register %d set to %d\"}",
                            extractJobId(payload), registerAddress, valueToWrite);
                    publish(jobReplyTopic, replyMsg);
                } else {
                    log.error("写入保持寄存器失败");

                    // 发送失败回复
                    String replyMsg = String.format("{\"jobId\":\"%s\", \"status\":\"error\", \"message\":\"Failed to write register %d\"}",
                            extractJobId(payload), registerAddress);
                    publish(jobReplyTopic, replyMsg);
                }
            } finally {
                ModbusTcpClientService.releasePriorityLock();
            }
        } catch (Exception e) {
            log.error("处理作业消息时发生异常", e);
        }
    }

    /**
     * 从消息中提取jobId
     */
    private String extractJobId(String payload) {
        try {
            // 简单JSON解析，实际应根据您的消息格式调整
            if (payload.contains("\"jobId\"")) {
                int start = payload.indexOf("\"jobId\"") + 8;
                int end = payload.indexOf("\"", start);
                return payload.substring(start, end);
            }
            return "unknown";
        } catch (Exception e) {
            log.warn("提取jobId失败", e);
            return "unknown";
        }
    }

    /**
     * 创建SSL Socket Factory
     */
    private SSLSocketFactory getSocketFactory() throws Exception {
        SSLContext sslContext = SSLContext.getInstance("TLSv1.2");
        sslContext.init(null, null, null);
        return sslContext.getSocketFactory();
    }

    /**
     * 发布消息
     */
    public void publish(String topic, String message) throws MqttException {
        if (mqttClient == null || !mqttClient.isConnected()) {
            initializeConnection();
        }

        MqttMessage mqttMessage = new MqttMessage(message.getBytes());
        mqttMessage.setQos(1); // QoS 1: 至少送达一次
        mqttClient.publish(topic, mqttMessage);
    }

    /**
     * 应用关闭时断开连接
     */
    @PreDestroy
    public void disconnect() {
        try {
            if (mqttClient != null && mqttClient.isConnected()) {
                mqttClient.disconnect();
                log.info("MQTT连接已断开");
            }
        } catch (MqttException e) {
            log.error("断开MQTT连接时出错: {}", e.getMessage());
        }
    }
}