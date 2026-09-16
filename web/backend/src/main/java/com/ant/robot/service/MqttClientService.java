package com.ant.robot.service;

import com.ant.robot.RobotSystem;
import com.ant.robot.utils.TaskConflictChecker;
import com.ant.robot.utils.XXTEAUtil;
import com.ant.robot.controller.DatabaseMigrator;
import com.ant.robot.controller.RevdataController;
import com.ant.robot.controller.RouteController;
import com.ant.robot.controller.SendToPlcController;
import com.ant.robot.mapper.RouteMapper;
import com.ant.robot.model.domain.Route;
import com.ant.robot.websocket.WebSocketServer;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;

import static com.ant.robot.RobotSystem.systemStartTimestamp;

@Slf4j
@Service
public class MqttClientService {
    private MqttClient mqttClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String brokerUrl = "tcp://47.105.58.240:1883";
    private final String clientId = "2b09c08701714ad49448d2c242ef7f6d";
    private final String username = "user_bah0wlb8";
    private final String password = "sn2EF9oprZNR";
    private String sessionKey;
    private boolean sessionKeyValid = false;
    private final OtaUpdateService otaUpdateService;
    private final VersionService versionService;
    String statusTopic = "/s/event/" + clientId;
    private static final String DEVICE_CODE = "2b09c08701714ad49448d2c242ef7f6d";
    private static final int KEY_LENGTH = 10;
    private boolean subscribed = false;
    private final Map<String, Long> topicLastMessageTime = new ConcurrentHashMap<>();

    @Autowired
    private RouteMapper routeMapper;
    @Autowired
    private WebSocketServer webSocketServer;
    @Autowired
    private RouteController routeController;
    @Autowired
    private TaskService taskService;
    @Autowired
    private RosOtaUpdateService rosOtaUpdateService;

    @Autowired
    public MqttClientService(@Lazy OtaUpdateService otaUpdateService,
                             VersionService versionService,
                             @Lazy RosOtaUpdateService rosOtaUpdateService) {
        this.otaUpdateService = otaUpdateService;
        this.versionService = versionService;
        this.rosOtaUpdateService = rosOtaUpdateService;
    }

    public String getClientId() {
        return clientId;
    }

    public void setSessionKey(String key) {
        log.info("key {}", key);
        this.sessionKey = key;
        this.sessionKeyValid = false;
    }

    private void reportInitialVersion() {
        String currentVersion = versionService.getCurrentVersion();
        reportVersion(currentVersion);
    }

    public void initializeConnection() throws MqttException {
        if (mqttClient != null && mqttClient.isConnected()) {
            log.info("MQTT连接已存在，跳过重新连接");
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
            options.setMaxInflight(10);
            options.setConnectionTimeout(10);

            mqttClient.setCallback(new MqttCallbackExtended() {
                @Override
                public void connectComplete(boolean reconnect, String serverURI) {
                    log.info("MQTT {}成功: {}", reconnect ? "重连" : "连接", serverURI);

                    if (reconnect) {
                        subscribed = false;
                        startSubscribing();
                    }
                }

                @Override
                public void connectionLost(Throwable cause) {
                    log.warn("MQTT连接丢失: {}", cause.getMessage());
                    subscribed = false;
                }

                @Override
                public void messageArrived(String topic, MqttMessage message) {
                    long receivedTime = System.currentTimeMillis();
                    topicLastMessageTime.put(topic, receivedTime);

                    if (isOldMessage(topic, receivedTime)) {
                        log.warn("消息到达时间超过系统启动时间，忽略旧消息: {}", topic);
                        return;
                    }

                    handleIncomingMessage(topic, message, receivedTime);
                }

                @Override
                public void deliveryComplete(IMqttDeliveryToken token) {
                    // 消息发布完成回调
                }
            });

            log.info("正在连接MQTT服务器: {}", brokerUrl);
            mqttClient.connect(options);
            subscribed = false;
            log.info("MQTT连接成功，等待系统准备就绪后订阅");
        } catch (MqttException e) {
            log.error("MQTT连接失败: {}", e.getMessage());
            log.error("连接详情: broker={}, clientId={}, username={}",
                    brokerUrl, clientId, username);

            if (mqttClient != null) {
                log.info("5秒后尝试重新连接...");
                try {
                    Thread.sleep(5000);
                    initializeConnection();
                } catch (Exception ex) {
                    log.error("重新连接失败", ex);
                }
            }
            throw e;
        }
    }

    private boolean isOldMessage(String topic, long receivedTime) {
        if (topic.contains("/ota/")) return false;

        Long systemStartTime = systemStartTimestamp.get();
        if (systemStartTime == null || systemStartTime == 0) {
            return false;
        }

        return receivedTime < systemStartTime;
    }

    private void handleIncomingMessage(String topic, MqttMessage message, long receivedTime) {
        try {
            if (!RobotSystem.isSystemReady()) {
                log.warn("系统未就绪，忽略主题 [{}] 的残留消息", topic);
                return;
            }

            String payload = new String(message.getPayload(), StandardCharsets.UTF_8);
            log.info("收到原始消息 [{}]: {}", topic, payload);

            // 需要解密的主题模式（包括BBB主题）
            boolean needDecrypt = topic.startsWith("/c/event/") ||
                    topic.startsWith("/c/ds/") ||
                    "BBB".equals(topic) ||
                    topic.startsWith("/c/attr/");

            String finalPayload = payload;
            log.info("收到原始消息 [{}]: {}", topic, payload);

            if (needDecrypt && sessionKeyValid && sessionKey != null) {
                try {
                    String encryptedData = null;

                    try {
                        JSONObject wrapper = new JSONObject(payload);
                        if (wrapper.has("data")) {
                            encryptedData = wrapper.getString("data");
                            log.debug("从JSON中提取data字段: {}", encryptedData);
                        }
                    } catch (JSONException e) {
                        encryptedData = payload;
                        log.debug("payload不是JSON格式，作为纯密文处理");
                    }

                    if (encryptedData != null) {
                        String decrypted = XXTEAUtil.decrypt(encryptedData, sessionKey);
                        if (decrypted != null) {
                            finalPayload = decrypted;
                            log.info("解密成功: {}", finalPayload);
                        } else {
                            log.error("解密返回null，可能密钥错误或数据损坏");
                            triggerKeyRenewal();
                            return;
                        }
                    } else {
                        log.warn("无法提取密文，跳过解密");
                    }
                } catch (Exception e) {
                    log.error("消息解密失败", e);
                    return;
                }
            }

            // 根据主题分发处理
            String commandTopic = "/c/ds/" + clientId;
            String eventTopic = "/c/event/" + clientId;
            String otaTopic = "/c/ota/" + clientId;
            String keyResultTopic = "/c/iot/key/result/" + clientId;
            String forbiddenTopic = "/c/iot/forbidden/" + clientId;
            String attrTopic = "/c/attr/" + clientId;

            if (keyResultTopic.equals(topic)) {
                handleKeyResultMessage(finalPayload);
            } else if (forbiddenTopic.equals(topic)) {
                handleForbiddenMessage(finalPayload);
            } else if (commandTopic.equals(topic)) {
                handleCommandMessage(finalPayload);
            } else if (eventTopic.equals(topic)) {
                handleStatusMessage(finalPayload);
            } else if (otaTopic.equals(topic)) {
                handleOtaMessage(finalPayload);
            } else if ("BBB".equals(topic)) {
                handleBBBCheckMessage(finalPayload);
            } else if (attrTopic.equals(topic)) {
                handleAttrMessage(finalPayload);  // 新增属性消息处理
            }else {
                log.warn("未知主题: {}", topic);
            }
        } catch (Exception e) {
            log.error("处理MQTT消息时出错", e);
        }
    }

    // 新增处理属性消息的方法
    private void handleAttrMessage(String payload) {
        try {
            // 解析指令
            JSONObject command = new JSONObject(payload);
            String instruction = command.optString("msg", "").trim();

            // 验证指令是否正确
            if ("check".equalsIgnoreCase(instruction)) {
                log.info("收到有效的属性检查指令");

                // 构建状态数据
                JSONObject statusData = buildAttrData();

                // 发布到/s/attr/{ClientID}主题并加密
                String attrTopic = "/s/attr/" + clientId;
                publish(attrTopic, statusData.toString());

                log.info("已发布属性数据到{}主题", attrTopic);
            } else {
                log.warn("收到无效的属性指令: {}", instruction);
            }
        } catch (JSONException e) {
            log.error("解析属性指令失败，无效的JSON格式: {}", payload, e);
        } catch (MqttException e) {
            log.error("发布属性数据到/s/attr/主题失败", e);
        } catch (Exception e) {
            log.error("处理属性指令时发生错误", e);
        }
    }

    // 新增构建属性数据的方法
    private JSONObject buildAttrData() throws JSONException {
        JSONObject data = new JSONObject();
        JSONObject attrData = new JSONObject();

        // 填充数据

        // 位置信息 - 使用两种格式
        attrData.put("location", String.format("%.2f,%.2f", RevdataController.Lon, RevdataController.Lat));
        attrData.put("location_gps", String.format("%.5f,%.5f", RevdataController.Lon, RevdataController.Lat));

        // 封装数据
        data.put("timestamp", System.currentTimeMillis());
        data.put("data", attrData);

        return data;
    }


    public void startSubscribing() {
        if (subscribed) {
            log.info("MQTT主题已订阅，无需重复操作");
            return;
        }

        if (mqttClient == null || !mqttClient.isConnected()) {
            log.warn("MQTT客户端未连接，无法订阅");
            return;
        }

        try {
            String[] topics = {
                    "/c/iot/key/result/" + clientId,
                    "/c/iot/forbidden/" + clientId,
                    "/c/ds/" + clientId,
                    "/c/event/" + clientId,
                    "/c/ota/" + clientId,
                    "/c/attr/" + clientId  // 新增属性主题订阅
            };

            for (String topic : topics) {
                mqttClient.subscribe(topic, 0);
                log.info("已订阅主题: {}", topic);
            }

            subscribed = true;
            log.info("所有主题订阅完成");
            reportInitialVersion();
        } catch (MqttException e) {
            log.error("订阅主题时出错: {}", e.getMessage());

            if (e.getReasonCode() == MqttException.REASON_CODE_CLIENT_NOT_CONNECTED) {
                log.info("尝试重新连接后订阅...");
                try {
                    initializeConnection();
                    Thread.sleep(1000);
                    startSubscribing();
                } catch (Exception ex) {
                    log.error("重新连接并订阅失败", ex);
                }
            }
        }
    }

    /**
     * 处理BBB主题消息
     */
    private void handleBBBCheckMessage(String payload) {
        try {
            // 解析指令
            JSONObject command = new JSONObject(payload);
            String instruction = command.optString("instruction", "").trim();

            // 验证指令是否正确
            if ("check".equalsIgnoreCase(instruction)) {
                log.info("收到有效的BBB检查指令");

                // 构建状态数据
                JSONObject statusData = buildStatusData();

                // 直接使用现有的publish方法发布到/s/attr/{ClientID}主题
                String attrTopic = "/s/attr/" + clientId;
                publish(attrTopic, statusData.toString());

                log.info("已发布状态到{}主题", attrTopic);
            } else {
                log.warn("收到无效的BBB指令: {}", instruction);
            }
        } catch (JSONException e) {
            log.error("解析BBB指令失败，无效的JSON格式: {}", payload, e);
        } catch (MqttException e) {
            log.error("发布状态到/s/attr/主题失败", e);
        } catch (Exception e) {
            log.error("处理BBB指令时发生错误", e);
        }
    }

    /**
     * 构建状态数据 (保持不变)
     */
    private JSONObject buildStatusData() throws JSONException {
        JSONObject statusData = new JSONObject();

        // 位置信息
        statusData.put("LocalLat", RevdataController.Lat);
        statusData.put("LocalLon", RevdataController.Lon);
        statusData.put("LocalAlt", 0); // 根据实际情况调整

        // 状态信息
        statusData.put("LOCSTATE", RevdataController.LOCSTATE);
        statusData.put("HEADSTATE", RevdataController.HEADSTATE);

        // 设备状态
        statusData.put("Can", RevdataController.canError);
        statusData.put("Mode", 0); // 根据实际情况调整
        statusData.put("Speed", 0); // 根据实际情况调整
        statusData.put("ActVoltage", RevdataController.ActVoltage);

        // 错误状态
        statusData.put("Status_ov_i", RevdataController.statusOvI);
        statusData.put("Status_ov_u", RevdataController.statusOvU);
        statusData.put("Status_err_enc", RevdataController.statusErrEnc);
        statusData.put("Status_ov_t", RevdataController.statusOvT);
        statusData.put("Status_ov_q", RevdataController.statusOvQ);
        statusData.put("Status_ov_load", RevdataController.statusOvLoad);


        // 时间戳
        statusData.put("timestamp", System.currentTimeMillis());

        return statusData;
    }

    // 以下是原有方法保持不变
    private void handleKeyResultMessage(String payload) {
        try {
            JSONObject json = new JSONObject(payload);
            if ("success".equals(json.getString("status"))) {
                sessionKeyValid = true;
                log.info("会话密钥验证成功");
            } else {
                sessionKeyValid = false;
                String reason = json.optString("reason", "未知原因");
                log.error("会话密钥验证失败: {}", reason);
                generateAndSendSessionKey();
            }
        } catch (Exception e) {
            log.error("解析密钥结果失败", e);
        }
    }

    private void handleForbiddenMessage(String payload) {
        try {
            JSONObject json = new JSONObject(payload);
            if ("renew_key".equals(json.getString("action"))) {
                log.info("收到密钥更新通知");
                generateAndSendSessionKey();
            }
        } catch (Exception e) {
            log.error("处理密钥到期通知失败", e);
        }
    }

    private void handleCommandMessage(String jsonPayload) {
        try {
            Map<String, String> messageMap = objectMapper.readValue(jsonPayload, Map.class);
            JSONObject command = new JSONObject(jsonPayload);
            String instruction = command.optString("msg", "").trim(); // 注意这里改为获取"msg"字段

            // 验证指令是否正确（不区分大小写）
            if ("check".equalsIgnoreCase(instruction)) {
                log.info("收到Check命令: " + jsonPayload);
                DatabaseMigrator migrator = new DatabaseMigrator();

                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    log.info("\n接收到关闭信号...");
                    migrator.stopAllMigrations();
                }));

                migrator.manualMigrateTables("t_task", "t_log", "t_run_log", "t_station", "t_route", "t_route_detail");
            }
        } catch (JsonProcessingException e) {
            log.error("解析JSON消息失败: " + e.getMessage());
        } catch (Exception e) {
            log.error("处理命令消息时出错: " + e.getMessage());
        }
    }

    private void handleStatusMessage(String jsonPayload) {
        Object taskIdObj = null;
        try {
            Map<String, Object> messageMap = objectMapper.readValue(jsonPayload, Map.class);
            taskIdObj = messageMap.get("taskId");
            if (taskIdObj == null) {
                log.warn("状态消息缺少taskId字段: {}", jsonPayload);
            }

            if (ModbusTcpClientService.connectionStatus.get() != 2) {
                JSONObject statusMessage = new JSONObject();
                statusMessage.put("taskId", taskIdObj);
                statusMessage.put("status", "Robot offline");
                publish(statusTopic, statusMessage.toString());
                return;
            }

            Object msgObj = messageMap.get("routeId");
            if (msgObj != null) {
                Long routeId = null;
                if (msgObj instanceof Integer) {
                    routeId = ((Integer) msgObj).longValue();
                } else if (msgObj instanceof Long) {
                    routeId = (Long) msgObj;
                } else if (msgObj instanceof String) {
                    try {
                        routeId = Long.parseLong((String) msgObj);
                    } catch (NumberFormatException e) {
                        log.error("路线ID格式错误: " + msgObj);
                        return;
                    }
                }

                String conflictReason = TaskConflictChecker.checkConflict(taskService);
                if (conflictReason != null) {
                    JSONObject response = new JSONObject()
                            .put("status", "rejected")
                            .put("reason", conflictReason)
                            .put("timestamp", System.currentTimeMillis());
                    publish(statusTopic, response.toString());
                    return;
                }

                if (routeId != null) {
                    QueryWrapper<Route> queryWrapper = new QueryWrapper<>();
                    queryWrapper.eq("id", routeId);
                    Route route = routeMapper.selectOne(queryWrapper);

                    if (route != null) {
                        log.info("找到路线ID: " + routeId);
                        List<Object> routeData = routeController.querymapRouteDetailData(routeId).getData();
                        JSONArray jsonArray = new JSONArray();
                        routeData.forEach(jsonArray::put);

                        JSONObject jsonObject = new JSONObject();
                        jsonObject.put("action", "gnsspoint");
                        jsonObject.put("points", jsonArray);

                        sendToPlc(jsonObject);

                        if (ModbusTcpClientService.writeSingleCoil(1, true)) {
                            log.info("运行成功");
                            TcpClientService.sendProgressUpdate("启动", "成功", "1");
                            JSONObject successStatus = new JSONObject();
                            successStatus.put("taskId", taskIdObj);
                            successStatus.put("status", "successful");
                            publish(statusTopic, successStatus.toString());
                        } else {
                            log.error("运行失败");
                            TcpClientService.sendProgressUpdate("启动", "失败", "0");
                            JSONObject errorStatus = new JSONObject();
                            errorStatus.put("taskId", taskIdObj);
                            errorStatus.put("status", "error");
                            publish(statusTopic, errorStatus.toString());
                        }
                    } else {
                        log.error("未找到路线ID: " + routeId);
                    }
                }
            }
        } catch (JsonProcessingException e) {
            log.error("解析JSON消息失败: " + e.getMessage());
        } catch (Exception e) {
            log.error("处理状态消息时出错: " + e.getMessage());
        }
    }

    private void handleOtaMessage(String jsonPayload) {
        try {
            JSONObject otaMsg = new JSONObject(jsonPayload);
            String taskId = otaMsg.getString("taskId");
            String module = otaMsg.getString("module");
            String version = otaMsg.getString("version");
            String firmwareUrl = otaMsg.getString("firmware_url");

            if ("web".equals(module)) {
                log.info("开始Web模块OTA更新, 任务ID: {}, 版本: {}", taskId, version);
                new Thread(() -> otaUpdateService.performOtaUpdate(firmwareUrl, version, taskId)).start();
            } else if ("ros".equals(module)) {
                log.info("开始ROS模块OTA更新, 任务ID: {}, 版本: {}", taskId, version);
                new Thread(() -> rosOtaUpdateService.performRosOtaUpdate(firmwareUrl, version, taskId)).start();
            } else {
                log.warn("不支持的OTA模块: {}", module);
                sendOtaStatus(taskId, OtaStatus.FAILED, 0, "不支持的模块类型: " + module);
            }
        } catch (Exception e) {
            log.error("解析OTA消息失败", e);
            sendOtaStatus("unknown", OtaStatus.FAILED, 0, "解析OTA消息失败: " + e.getMessage());
        }
    }

    public void sendOtaStatus(String taskId, OtaStatus status, int progress, String message) {
        try {
            JSONObject statusMsg = new JSONObject();
            statusMsg.put("taskId", taskId);
            statusMsg.put("status", status.name());
            statusMsg.put("progress", progress);
            statusMsg.put("message", message);
            statusMsg.put("timestamp", System.currentTimeMillis());

            // 统一使用同一个主题上报状态
            String topic = "/s/ota/result/" + clientId;
            publish(topic, statusMsg.toString());
            log.info("OTA状态上报: {}", statusMsg);
        } catch (MqttException | JSONException e) {
            log.error("发送OTA状态失败", e);
        }
    }

    public enum OtaStatus {
        IDLE, DOWNLOADING, VERIFYING, READY_TO_UPDATE,
        BACKUP, UPDATING, ROLLBACK, SUCCESS, FAILED, CANCELED
    }

    public void publish(String topic, String message) throws MqttException {
        boolean needEncrypt = topic.startsWith("/s/event/") ||
                topic.startsWith("/s/attr/");

        boolean isOtaTopic = topic.contains("/ota/");

        String finalMessage = message;
        log.info("原始消息: {}", message);

        if (needEncrypt && !isOtaTopic && sessionKey != null) {
            try {
                String encrypted = XXTEAUtil.encrypt(message, sessionKey);
                log.info("info: {}", encrypted);

                JSONObject wrapper = new JSONObject();
                wrapper.put("data", encrypted);
                finalMessage = wrapper.toString();
            } catch (Exception e) {
                log.error("消息加密失败", e);
            }
        }

        //if (mqttClient != null && mqttClient.isConnected()) {
            MqttMessage mqttMessage = new MqttMessage(finalMessage.getBytes());
            mqttMessage.setQos(0);
            mqttClient.publish(topic, mqttMessage);
            log.info("已发送消息到主题 [{}]: {}", topic, finalMessage);
        /*} else {
            log.error("MQTT客户端未连接，无法发送消息");
            throw new MqttException(MqttException.REASON_CODE_CLIENT_NOT_CONNECTED);
        }*/
    }

    public void subscribe(String topic) throws MqttException {
        if (mqttClient == null || !mqttClient.isConnected()) {
            initializeConnection();
        }

        mqttClient.subscribe(topic, 1);
        log.info("已订阅主题: " + topic);
    }

    public void reportVersion(String webVersion) {
        try {
            JSONObject versionMsg = new JSONObject();
            versionMsg.put("web", webVersion);
            versionMsg.put("ros", versionService.getRosVersion()); // 新增ROS版本号

            String versionTopic = "/s/ota/" + clientId;
            publish(versionTopic, versionMsg.toString());
            log.info("上报版本信息: {}", versionMsg);

        } catch (MqttException | JSONException e) {
            log.error("上报版本信息失败", e);
        }
    }

    @PreDestroy
    public void disconnect() {
        try {
            if (mqttClient != null && mqttClient.isConnected()) {
                mqttClient.disconnect();
                log.info("MQTT连接已断开");
            }
        } catch (MqttException e) {
            log.error("断开MQTT连接时出错: " + e.getMessage());
        }
    }

    private void sendToPlc(JSONObject jsonObject) {
        if (!webSocketServer.isPlcTaskRunning.compareAndSet(false, true)) {
            log.error("已有PLC任务正在执行，无法重复提交");
            throw new IllegalStateException("已有任务正在执行");
        }
        try {
            Future<?> future = webSocketServer.executorService.submit(() -> {
                try {
                    ModbusTcpClientService.acquirePriorityLock(ModbusTcpClientService.PATH_PRIORITY);
                    ModbusTcpClientService.writeSingleCoil(3, true);
                    SendToPlcController.pathpointToPlc(jsonObject);
                } catch (Exception e) {
                    log.error("PLC任务执行异常");
                    throw new RuntimeException("PLC任务异常", e);
                } finally {
                    ModbusTcpClientService.releasePriorityLock();
                    webSocketServer.isPlcTaskRunning.set(false);
                }
            });
        } catch (RejectedExecutionException e) {
            webSocketServer.isPlcTaskRunning.set(false);
            log.error("任务提交被拒绝，线程池已满");
            throw e;
        }
    }

    public void generateAndSendSessionKey() {
        String rNum = generateRandomString(KEY_LENGTH);
        String encryptedKey = XXTEAUtil.encrypt(rNum, DEVICE_CODE);
        log.info("rNum{}", rNum);
        setSessionKey(rNum);
        try {
            JSONObject keyMessage = new JSONObject();
            keyMessage.put("encryptedKey", encryptedKey);
            MqttMessage mqttMessage = new MqttMessage(keyMessage.toString().getBytes());
            mqttMessage.setQos(0);
            mqttClient.publish("/s/iot/key/" + clientId, mqttMessage);
            log.info("已发送消息到主题 [{}]: {}", ("/s/iot/key/" + clientId), keyMessage);
        } catch (Exception e) {
            log.error("发送会话密钥失败", e);
        }
    }

    private String generateRandomString(int length) {
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        Random random = new Random();
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(chars.charAt(random.nextInt(chars.length())));
        }
        return sb.toString();
    }

    private boolean isDuplicateMessage(String topic, long currentTime) {
        Long lastReceivedTime = topicLastMessageTime.get(topic);
        if (lastReceivedTime != null) {
            return (currentTime - lastReceivedTime) < 1000;
        }
        return false;
    }

    private void triggerKeyRenewal() {
        log.warn("触发紧急密钥更新...");
        sessionKeyValid = false;
        generateAndSendSessionKey();
    }
}