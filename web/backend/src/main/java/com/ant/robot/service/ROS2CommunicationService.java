package com.ant.robot.service;

import cn.hutool.json.JSONObject;
import com.ant.robot.controller.Llh2xyzController;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.ant.robot.model.domain.Param;
import com.ant.robot.mapper.ParamMapper;
import com.ant.robot.websocket.WebSocketServer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import geometry_msgs.msg.*;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.ros2.rcljava.RCLJava;
import org.ros2.rcljava.consumers.Consumer;
import org.ros2.rcljava.interfaces.MessageDefinition;
import org.ros2.rcljava.node.Node;
import org.ros2.rcljava.publisher.Publisher;
import org.ros2.rcljava.qos.QoSProfile;
import org.ros2.rcljava.qos.policies.Durability;
import org.ros2.rcljava.qos.policies.Reliability;
import org.ros2.rcljava.subscription.Subscription;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Service;
import std_msgs.msg.*;

import java.lang.String;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

@Slf4j
@Service
public class ROS2CommunicationService implements SmartLifecycle {

    private Node node;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private static final ObjectMapper objectMapper = new ObjectMapper();

    // 依赖注入
    private final ParamMapper paramMapper;
    private final Llh2xyzController llh2xyzController;

    // 本地原点坐标
    private double Local_Lat = 0.0;
    private double Local_Lon = 0.0;

    // 车辆状态变量
    private double Lat = 0.0;
    private double Lon = 0.0;
    private double High = 0.0;
    private double Heading = 0.0;
    private double LocalX = 0.0;
    private double LocalY = 0.0;
    private double X = 0.0;
    private double Y = 0.0;
    private double Z = 0.0;
    private double W = 0.0;

    public ROS2CommunicationService(ParamMapper paramMapper, Llh2xyzController llh2xyzController) {
        this.paramMapper = paramMapper;
        this.llh2xyzController = llh2xyzController;
    }

    // 订阅器
    private Subscription<UInt32> plcStatusSub;
    private Subscription<UInt32> navStationIdSub;
    private Subscription<UInt32> routeIdSub;
    private Subscription<UInt8> goalFinishSub;
    private Subscription<UInt8> routeSerialNumberSub;
    private Subscription<PoseStamped> vehiclePoseSub;
    private Subscription<std_msgs.msg.String> buildMapStatusSub;

    private volatile String buildMapStatus = "unknown";
    private final Object buildMapStatusMonitor = new Object();

    // 发布器
    private Publisher<Float32> spinActionPub;
    private static Publisher<Float64MultiArray> pathPointPub;
    private Publisher<Float64MultiArray> pathPointLeftPub;
    private Publisher<Float64MultiArray> pathPointRightPub;
    private Publisher<UInt8> carRunStarPub;
    private Publisher<UInt8> laneChangeRulePub;
    private static Publisher<Twist> webRemoteCtrlDataPub;
    private static Publisher<UInt8> RemoteCtrlAutomaticSwitchPub;
    private Publisher<UInt8> vehicleRunStarPublisher;
    private Publisher<UInt8> closeRoutePublisher;
    private Publisher<Int32> planstarPublisher;
    private Publisher<Int32> plansavePublisher;
    private Publisher<Int32> terminatePublisher;
    private Publisher<std_msgs.msg.String> buildmapPublisher;
    private Publisher<std_msgs.msg.String> mapPathPublisher;
    private Publisher<std_msgs.msg.String> mapPathNavPublisher;
    private Publisher<PointStamped> clickedpointPublisher;
    private Publisher<std_msgs.msg.String> routeNamePublisher;
    private Publisher<std_msgs.msg.String> mapCoveragePublisher;
    private Publisher<PoseStamped> pgmHomeTfPublisher;
    private Publisher<PoseWithCovarianceStamped> initialPosePublisher;
    private Publisher<Float64MultiArray> zThresholdPublisher;
    private Publisher<PoseWithCovarianceStamped> initialPublisher;
    private Publisher<UInt8> plcStartPublisher;
    private Publisher<UInt32> routeIdPublisher;
    private static Publisher<Twist> cmdVelPub;
    private Publisher<std_msgs.msg.String> pcdfolderPublisher;
    private Publisher<std_msgs.msg.String> mapfolderPublisher;
    

    // 回调接口
    public interface ROS2MessageCallback {
        void onMessageReceived(Object message) throws Exception;
    }

    private ROS2MessageCallback messageCallback;

    public void setMessageCallback(ROS2MessageCallback callback) {
        this.messageCallback = callback;
    }

    @PostConstruct
    public void init() {
        try {
            log.info("正在初始化ROS2通信服务...");

            // 检查RCLJava是否已初始化
            if (!RCLJava.ok()) {
                RCLJava.rclJavaInit();
            }

            // 创建节点
            node = RCLJava.createNode("java_ros2_bridge");

            // 等待节点完全初始化
            Thread.sleep(2000);

            log.info("ROS2节点创建成功，等待发现已有发布器...");

            // 先初始化发布器
            initializePublishers();

            // 等待一段时间让其他节点发现本节点
            Thread.sleep(3000);

            // 然后初始化订阅器
            initializeSubscriptions();

            // 额外的发现等待时间
            log.info("等待ROS2网络发现完成...");
            Thread.sleep(5000);

            running.set(true);

            // 启动spinning线程
            scheduler.scheduleAtFixedRate(this::spinROS2, 0, 100, TimeUnit.MILLISECONDS);

            // 启动发现检查线程
            scheduler.scheduleAtFixedRate(this::checkDiscoveryStatus, 10, 30, TimeUnit.SECONDS);

            log.info("ROS2通信服务初始化成功");

        } catch (Exception e) {
            log.error("ROS2通信服务初始化失败: ", e);
            running.set(false);
            scheduleRetry();
        }
    }

    private void scheduleRetry() {
        scheduler.schedule(() -> {
            if (!running.get()) {
                log.info("尝试重新初始化ROS2服务...");
                init();
            }
        }, 5, TimeUnit.SECONDS);
    }

    private void initializeSubscriptions() {
        try {
            log.info("开始初始化订阅器...");

            // 使用重试机制创建订阅器，现在使用正确的Consumer类型
            plcStatusSub = createSubscriptionWithRetry(
                    "plc_status", UInt32.class, this::handlePlcStatus, 5, 2000);

            navStationIdSub = createSubscriptionWithRetry(
                    "path_point_id", UInt32.class, this::handleNavStationId, 5, 2000);

            routeIdSub = createSubscriptionWithRetry(
                    "route_id", UInt32.class, this::handleRouteId, 5, 2000);

            goalFinishSub = createSubscriptionWithRetry(
                    "goal_finish", UInt8.class, this::handleGoalFinish, 5, 2000);

            routeSerialNumberSub = createSubscriptionWithRetry(
                    "route_serial_number", UInt8.class, this::handleRouteSerialNumber, 5, 2000);

            vehiclePoseSub = createSubscriptionWithRetry(
                    "tf_pose", PoseStamped.class, this::handleVehiclePose, 10, 3000);
            QoSProfile buildMapStatusQos = QoSProfile.defaultProfile()
                    .setDurability(Durability.TRANSIENT_LOCAL)
                    .setReliability(Reliability.RELIABLE);
            buildMapStatusSub = createSubscriptionWithRetry(
                    "buildmap_status", std_msgs.msg.String.class,
                    this::handleBuildMapStatus, 10, 2000, buildMapStatusQos);

            log.info("所有订阅器初始化完成");

        } catch (Exception e) {
            log.error("初始化订阅器失败: ", e);
        }
    }

    /**
     * 修复：使用正确的Consumer类型创建订阅器
     */
    private <T extends MessageDefinition> Subscription<T> createSubscriptionWithRetry(String topicName,
                                                                                      Class<T> messageType,
                                                                                      java.util.function.Consumer<T> callback,
                                                                                      int maxRetries,
                                                                                      long retryIntervalMs) {
        return createSubscriptionWithRetry(topicName, messageType, callback, maxRetries, retryIntervalMs,
                QoSProfile.DEFAULT);
    }

    private <T extends MessageDefinition> Subscription<T> createSubscriptionWithRetry(String topicName,
                                                                                      Class<T> messageType,
                                                                                      java.util.function.Consumer<T> callback,
                                                                                      int maxRetries,
                                                                                      long retryIntervalMs,
                                                                                      QoSProfile qosProfile) {
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                log.info("尝试创建订阅器: {} (尝试 {}/{})", topicName, attempt, maxRetries);

                // 修复：使用RCLJava的Consumer类型包装我们的回调
                Consumer<T> rosConsumer = new Consumer<T>() {
                    @Override
                    public void accept(T message) {
                        callback.accept(message);
                    }
                };

                Subscription<T> subscription = node.<T>createSubscription(
                        messageType,
                        "/" + topicName,
                        rosConsumer,
                        qosProfile
                );

                log.info("订阅器创建成功: {}", topicName);
                return subscription;

            } catch (Exception e) {
                log.warn("创建订阅器失败({}/{}): {}, topic: {}",
                        attempt, maxRetries, e.getMessage(), topicName);

                if (attempt < maxRetries) {
                    try {
                        log.info("等待 {}ms 后重试...", retryIntervalMs);
                        Thread.sleep(retryIntervalMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }

        log.error("创建订阅器最终失败: {}", topicName);
        return null;
    }

    private void initializePublishers() {
        try {
            log.info("初始化发布器...");

            spinActionPub = node.<Float32>createPublisher(Float32.class, "/spin_action");
            pathPointPub = node.<Float64MultiArray>createPublisher(Float64MultiArray.class, "/path_point");
            pathPointLeftPub = node.<Float64MultiArray>createPublisher(Float64MultiArray.class, "/path_point_left");
            pathPointRightPub = node.<Float64MultiArray>createPublisher(Float64MultiArray.class, "/path_point_right");
            carRunStarPub = node.<UInt8>createPublisher(UInt8.class, "/vehicle_run_star");
            laneChangeRulePub = node.<UInt8>createPublisher(UInt8.class, "/lane_change_rule");
            webRemoteCtrlDataPub = node.<Twist>createPublisher(Twist.class, "/WebRemoteCtrlData");
            RemoteCtrlAutomaticSwitchPub = node.<UInt8>createPublisher(UInt8.class, "/RemoteCtrlAutomaticSwitch");
            vehicleRunStarPublisher = node.<UInt8>createPublisher(UInt8.class, "/vehicle_run_star");
            closeRoutePublisher = node.<UInt8>createPublisher(UInt8.class, "/close_route");
            planstarPublisher = node.createPublisher(Int32.class, "/plan_start");
            plansavePublisher = node.createPublisher(Int32.class, "/path_save");
            terminatePublisher = node.createPublisher(Int32.class, "/terminate");
            buildmapPublisher = node.createPublisher(std_msgs.msg.String.class, "/buildmap");
            mapPathPublisher = node.createPublisher(std_msgs.msg.String.class, "/pcd_path_name");
            mapPathNavPublisher = node.createPublisher(std_msgs.msg.String.class, "/map_path");
            clickedpointPublisher = node.createPublisher(PointStamped.class, "/clicked_point");
            routeNamePublisher = node.createPublisher(std_msgs.msg.String.class, "/routeName");
            mapCoveragePublisher = node.createPublisher(std_msgs.msg.String.class, "/mapCoverage");
            pgmHomeTfPublisher = node.createPublisher(PoseStamped.class, "/pgm_home_tf");
            initialPosePublisher = node.createPublisher(PoseWithCovarianceStamped.class, "/initialpose");
            zThresholdPublisher = node.<Float64MultiArray>createPublisher(Float64MultiArray.class, "/z_threshold");
            plcStartPublisher = node.<UInt8>createPublisher(UInt8.class, "/plc_start");
            routeIdPublisher = node.<UInt32>createPublisher(UInt32.class, "/route_id");
            cmdVelPub = node.<Twist>createPublisher(Twist.class, "/cmd_vel");
            pcdfolderPublisher = node.createPublisher(std_msgs.msg.String.class, "/pcd_folder");
            mapfolderPublisher = node.createPublisher(std_msgs.msg.String.class, "/map_folder");

            log.info("发布器初始化完成");

        } catch (Exception e) {
            log.error("初始化发布器失败: ", e);
            throw new RuntimeException("发布器初始化失败", e);
        }
    }

    /**
     * 检查ROS2发现状态
     */
    private void checkDiscoveryStatus() {
        if (!running.get() || node == null) {
            return;
        }

        try {
            log.debug("ROS2发现状态检查 - 节点运行状态: {}", running.get());

            // 检查关键话题的发布器状态
            checkTopicPublishers();

        } catch (Exception e) {
            log.error("检查发现状态时出错: {}", e.getMessage());
        }
    }

    /**
     * 检查关键话题的发布器状态
     */
    private void checkTopicPublishers() {
        String[] criticalTopics = {
                "/vehicle_pose",
                "/plc_status",
                "/path_point_id",
                "/route_id",
                "/goal_finish",
                "/route_serial_number"
        };

        for (String topic : criticalTopics) {
            try {
                log.debug("检查话题: {}", topic);
            } catch (Exception e) {
                log.debug("检查话题 {} 时出错: {}", topic, e.getMessage());
            }
        }
    }

    /**
     * 手动触发重新发现
     */
    public boolean triggerRediscovery() {
        try {
            log.info("手动触发ROS2重新发现...");

            // 暂停spinning
            running.set(false);
            Thread.sleep(1000);

            // 重新初始化订阅器
            reinitializeSubscriptions();

            // 恢复spinning
            running.set(true);

            log.info("重新发现完成");
            return true;

        } catch (Exception e) {
            log.error("手动重新发现失败: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 重新初始化订阅器
     */
    private void reinitializeSubscriptions() {
        try {
            log.info("重新初始化所有订阅器...");

            // 清理现有订阅器
            cleanupSubscriptions();

            // 等待清理完成
            Thread.sleep(2000);

            // 重新初始化
            initializeSubscriptions();

            // 额外等待发现时间
            Thread.sleep(5000);

            log.info("订阅器重新初始化完成");

        } catch (Exception e) {
            log.error("重新初始化订阅器失败: {}", e.getMessage());
        }
    }

    /**
     * 清理订阅器
     */
    private void cleanupSubscriptions() {
        try {
            List<Subscription<?>> subscriptions = Arrays.asList(
                    plcStatusSub, navStationIdSub, routeIdSub,
                    goalFinishSub, routeSerialNumberSub, vehiclePoseSub,
                    buildMapStatusSub
            );

            for (Subscription<?> sub : subscriptions) {
                if (sub != null) {
                    try {
                        sub.dispose();
                    } catch (Exception e) {
                        log.warn("清理订阅器时出错: {}", e.getMessage());
                    }
                }
            }

            // 重置为null
            plcStatusSub = null;
            navStationIdSub = null;
            routeIdSub = null;
            goalFinishSub = null;
            routeSerialNumberSub = null;
            vehiclePoseSub = null;
            buildMapStatusSub = null;

        } catch (Exception e) {
            log.error("清理订阅器时发生错误: {}", e.getMessage());
        }
    }

    private void spinROS2() {
        if (!running.get() || !RCLJava.ok() || node == null) {
            log.warn("ROS2 spinning条件不满足，跳过本次spin");
            return;
        }

        try {
            RCLJava.spinOnce(node);

        } catch (NullPointerException e) {
            log.error("ROS2 spinning空指针异常: {}", e.getMessage());
            recoverFromNullPointer();

        } catch (Exception e) {
            log.error("ROS2 spinning错误: {}", e.getMessage());

            if (!RCLJava.ok()) {
                log.warn("ROS2上下文异常，尝试恢复...");
                running.set(false);
                scheduleRetry();
            }
        }
    }

    /**
     * 从空指针异常中恢复
     */
    private void recoverFromNullPointer() {
        try {
            log.info("尝试从空指针异常恢复...");

            if (messageCallback != null) {
                ROS2MessageCallback tempCallback = messageCallback;
                messageCallback = null;

                scheduler.schedule(() -> {
                    messageCallback = tempCallback;
                    log.info("消息回调已恢复");
                }, 2, TimeUnit.SECONDS);
            }

            if (node != null) {
                reinitializeSubscriptions();
            }

        } catch (Exception e) {
            log.error("从空指针异常恢复失败: {}", e.getMessage());
        }
    }

    /**
     * 处理车辆位姿消息 - 增强空值检查
     */
    private void handleVehiclePose(PoseStamped msg) {
        try {
            // 空值检查
            if (msg == null) {
                log.warn("收到空的车辆位姿消息");
                return;
            }

            Pose pose = msg.getPose();
            if (pose == null) {
                log.warn("车辆位姿消息中的pose为空");
                return;
            }

            // 提取位置信息
            Point position = pose.getPosition();
            Quaternion orientation = pose.getOrientation();

            // 空值检查
            if (position == null || orientation == null) {
                log.warn("位置或方向数据为空");
                return;
            }

            // 安全获取坐标值
            double x = safeGetDouble(position::getX, "position X");
            double y = safeGetDouble(position::getY, "position Y");
            double z = safeGetDouble(position::getZ, "position Z");

            // 更新本地坐标
            LocalX = x;
            LocalY = y;
            High = z;

            // 安全获取四元数值
            X = safeGetDouble(orientation::getX, "orientation X");
            Y = safeGetDouble(orientation::getY, "orientation Y");
            Z = safeGetDouble(orientation::getZ, "orientation Z");
            W = safeGetDouble(orientation::getW, "orientation W");

            // 计算航向角
            Heading = quaternionToHeading(orientation);
            
            QueryWrapper<Param> paramQueryWrapper = new QueryWrapper<>();
            Param param = paramMapper.selectOne(paramQueryWrapper);
            Local_Lat = Double.parseDouble(param.getLocal_origin_latitude());
            Local_Lon = Double.parseDouble(param.getLocal_origin_longitude());

            // 转换为经纬度
            if (llh2xyzController != null) {
                double[] latLon = llh2xyzController.convertFromLocalCoordinates(
                        Local_Lat, Local_Lon, x, y, z);

                if (latLon != null && latLon.length >= 2) {
                    Lat = latLon[0];
                    Lon = latLon[1];

                    // 广播到前端
                    safeBroadcastPose();
                } else {
                    log.warn("坐标转换返回空结果");
                }
            } else {
                log.warn("llh2xyzController为空，跳过坐标转换");
            }

            // 安全调用回调接口
            safeInvokeCallback(msg, "车辆位姿");

            log.debug("处理车辆位姿成功: LocalX={}, LocalY={}", x, y);

        } catch (Exception e) {
            log.error("处理车辆位姿消息时发生错误: {}", e.getMessage(), e);
        }
    }

    /**
     * 安全获取double值的辅助方法
     */
    private double safeGetDouble(Supplier<Double> supplier, String fieldName) {
        try {
            Double value = supplier.get();
            return value != null ? value : 0.0;
        } catch (Exception e) {
            log.warn("获取{}失败，使用默认值0.0: {}", fieldName, e.getMessage());
            return 0.0;
        }
    }

    /**
     * 安全广播车辆位姿到前端
     */
    private void safeBroadcastPose() {
        try {
            JSONObject poseData = new JSONObject();
            poseData.put("action", "carCurrentPosition");

            // 安全构建position对象
            JSONObject positionData = new JSONObject();
            positionData.put("Lat", Double.isNaN(Lat) ? 0.0 : Lat);
            positionData.put("Lon", Double.isNaN(Lon) ? 0.0 : Lon);
            positionData.put("High", Double.isNaN(High) ? 0.0 : High);
            positionData.put("X", Double.isNaN(X) ? 0.0 : X);
            positionData.put("Y", Double.isNaN(Y) ? 0.0 : Y);
            positionData.put("Z", Double.isNaN(Z) ? 0.0 : Z);
            positionData.put("W", Double.isNaN(W) ? 0.0 : W);
            positionData.put("Heading", Double.isNaN(Heading) ? 0.0 : Heading);
            positionData.put("LocalX", Double.isNaN(LocalX) ? 0.0 : LocalX);
            positionData.put("LocalY", Double.isNaN(LocalY) ? 0.0 : LocalY);

            poseData.put("position", positionData);

            // 安全发送WebSocket消息

            String message = poseData.toString();
            if (message != null && !message.trim().isEmpty()) {
                WebSocketServer.sendInfo(message, null);
                log.debug("车辆位姿广播成功");
            } else {
                log.warn("WebSocket消息为空，跳过发送");
            }

        } catch (Exception e) {
            log.error("广播车辆位姿到前端时发生错误: {}", e.getMessage(), e);
        }
    }

    /**
     * 安全调用消息回调
     */
    private void safeInvokeCallback(Object message, String messageType) {
        if (messageCallback != null) {
            try {
                messageCallback.onMessageReceived(message);
            } catch (NullPointerException e) {
                log.error("消息回调空指针异常 ({}): {}", messageType, e.getMessage());
            } catch (Exception e) {
                log.error("消息回调处理异常 ({}): {}", messageType, e.getMessage());
            }
        }
    }

    // 其他消息处理方法也添加安全调用
    private void handlePlcStatus(UInt32 msg) {
        safeInvokeCallback(msg, "PLC状态");
        log.debug("收到PLC状态: {}", msg != null ? msg.getData() : "null");
    }

    private void handleBuildMapStatus(std_msgs.msg.String msg) {
        if (msg == null || msg.getData() == null) {
            return;
        }

        String status = msg.getData().trim();
        if (status.isEmpty()) {
            return;
        }

        synchronized (buildMapStatusMonitor) {
            buildMapStatus = status;
            buildMapStatusMonitor.notifyAll();
        }
        log.info("收到建图状态: {}", status);
    }

    public void clearBuildMapStatus() {
        synchronized (buildMapStatusMonitor) {
            buildMapStatus = "command_pending";
        }
    }

    public String getBuildMapStatus() {
        return buildMapStatus;
    }

    public String waitForBuildMapReady(long timeoutMs) {
        return waitForBuildMapStatus(timeoutMs,
                "map_data_ready", "already_mapping",
                "start_livox_failed", "start_lio_sam_failed");
    }

    public String waitForBuildMapSave(long timeoutMs) {
        return waitForBuildMapStatus(timeoutMs,
                "saved:", "save_failed", "not_mapping",
                "no_map_data", "invalid_map_name");
    }

    private String waitForBuildMapStatus(long timeoutMs, String... terminalStatuses) {
        long deadline = System.currentTimeMillis() + Math.max(timeoutMs, 1L);
        synchronized (buildMapStatusMonitor) {
            while (!matchesBuildMapStatus(buildMapStatus, terminalStatuses)) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    return buildMapStatus;
                }
                try {
                    buildMapStatusMonitor.wait(Math.min(remaining, 1000L));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return "interrupted";
                }
            }
            return buildMapStatus;
        }
    }

    private boolean matchesBuildMapStatus(String status, String... terminalStatuses) {
        if (status == null) {
            return false;
        }
        for (String terminalStatus : terminalStatuses) {
            if (terminalStatus.endsWith(":") && status.startsWith(terminalStatus)) {
                return true;
            }
            if (status.equals(terminalStatus)) {
                return true;
            }
        }
        return false;
    }

    private void handleNavStationId(UInt32 msg) {
        safeInvokeCallback(msg, "导航站ID");
        log.debug("收到导航站ID: {}", msg != null ? msg.getData() : "null");
    }

    private void handleRouteId(UInt32 msg) {
        safeInvokeCallback(msg, "路线ID");
        log.debug("收到路线ID: {}", msg != null ? msg.getData() : "null");
    }

    private void handleGoalFinish(UInt8 msg) {
        safeInvokeCallback(msg, "目标完成状态");
        log.debug("收到目标完成状态: {}", msg != null ? msg.getData() : "null");
    }

    private void handleRouteSerialNumber(UInt8 msg) {
        safeInvokeCallback(msg, "路线序列号");
        log.debug("收到路线序列号: {}", msg != null ? msg.getData() : "null");
    }

    /**
     * 将四元数转换为航向角（偏航角）
     */
    private double quaternionToHeading(Quaternion quat) {
        try {
            if (quat == null) return 0.0;

            double x = safeGetDouble(quat::getX, "quaternion X");
            double y = safeGetDouble(quat::getY, "quaternion Y");
            double z = safeGetDouble(quat::getZ, "quaternion Z");
            double w = safeGetDouble(quat::getW, "quaternion W");

            // 计算偏航角（yaw）
            double yaw = Math.atan2(2.0 * (w * z + x * y), 1.0 - 2.0 * (y * y + z * z));

            // 转换为角度
            return Math.toDegrees(yaw);
        } catch (Exception e) {
            log.error("四元数转换航向角失败: {}", e.getMessage());
            return 0.0;
        }
    }

    // 以下为原有的发布方法，保持不变
    public boolean publishVehicleStartSignal() {
        try {
            UInt8 plcMsg = new UInt8();
            plcMsg.setData((byte) 1);
            plcStartPublisher.publish(plcMsg);

            UInt8 msg = new UInt8();
            msg.setData((byte) 1);
            vehicleRunStarPublisher.publish(msg);
            log.info("已发布车辆启动信号: /plc_start = 1, /vehicle_run_star = 1");
            return true;
        } catch (Exception e) {
            log.error("发布车辆启动信号失败", e);
            return false;
        }
    }

    public boolean publishVehiclePauseSignal() {
        try {
            UInt8 msg = new UInt8();
            msg.setData((byte) 0);
            vehicleRunStarPublisher.publish(msg);

            UInt8 plcMsg = new UInt8();
            plcMsg.setData((byte) 0);
            plcStartPublisher.publish(plcMsg);
            log.info("已发布车辆暂停信号: /vehicle_run_star = 0, /plc_start = 0");
            return true;
        } catch (Exception e) {
            log.error("发布车辆暂停信号失败", e);
            return false;
        }
    }

    public boolean plcStartPublisherSignal() {
        try {
            UInt8 msg = new UInt8();
            msg.setData((byte) 1);
            plcStartPublisher.publish(msg);
            log.info("已发布plc启动信号: /plc_start = 1");
            return true;
        } catch (Exception e) {
            log.error("发布plc启动信号失败", e);
            return false;
        }
    }

    public boolean routeIdPublisherSignal(Long id) {
        try {
            UInt32 msg = new UInt32();
            msg.setData(Math.toIntExact(id));
            routeIdPublisher.publish(msg);
            log.info("已发布路线id {}",id);
            return true;
        } catch (Exception e) {
            log.error("发布路线id失败", e);
            return false;
        }
    }



    public boolean publishTaskCancelSignal() {
        try {
            UInt8 msg = new UInt8();
            msg.setData((byte) 1);
            closeRoutePublisher.publish(msg);
            log.info("已发布任务取消信号: /close_route = 1");
            return true;
        } catch (Exception e) {
            log.error("发布任务取消信号失败", e);
            return false;
        }
    }

    public static boolean publishWebRemoteCtrlData(String jsonData) {
        try {
            JsonNode rootNode = objectMapper.readTree(jsonData);

            String action = rootNode.get("action").asText();
            if (!"WebRemoteCtrlData".equals(action)) {
                log.warn("未知的action类型: {}", action);
                return false;
            }

            JsonNode remoteNode = rootNode.get("remote");
            if (remoteNode == null) {
                log.error("JSON数据中缺少remote字段");
                return false;
            }

            float ctrX1 = (float) (remoteNode.get("CTR_X1").asDouble() / 100.0);
            float ctrY1 = (float) (remoteNode.get("CTR_Y1").asDouble() / 100.0);
            float ctrX2 = (float) (0 - remoteNode.get("CTR_X2").asDouble() / 100.0);
            float ctrY2 = (float) (remoteNode.get("CTR_Y2").asDouble() / 100.0);

            try {
                Twist msg = new Twist();
                
                // 设置线速度
                Vector3 linear = new Vector3();
                linear.setX(ctrY1);
                linear.setY(0);
                linear.setZ(0);
                msg.setLinear(linear);
                
                // 设置角速度
                Vector3 angular = new Vector3();
                angular.setX(0);
                angular.setY(0);
                angular.setZ(ctrX2);
                msg.setAngular(angular);
                
                webRemoteCtrlDataPub.publish(msg);
                
                //log.info("发布速度控制命令 - 线速度[{}, {}, {}], 角速度[{}, {}, {}]", linearX, linearY, linearZ, angularX, angularY, angularZ);
                return true;
                
            } catch (Exception e) {
                log.error("发布速度控制命令失败", e);
                return false;
            }

        } catch (Exception e) {
            log.error("处理JSON数据时发生错误: {}", e.getMessage());
            return false;
        }
    }

    public boolean publishClickedPoint(double x, double y, double z) {
        try {
            PointStamped msg = new PointStamped();

            try {
                std_msgs.msg.Header header = new std_msgs.msg.Header();
                header.setFrameId("map");
                msg.setHeader(header);
            } catch (Exception e) {
                log.warn("设置header失败: {}", e.getMessage());
            }

            Point point = new Point();
            point.setX(x);
            point.setY(y);
            point.setZ(z);
            msg.setPoint(point);

            clickedpointPublisher.publish(msg);

            log.info("发布点击点坐标: x={}, y={}, z={}", x, y, z);
            return true;

        } catch (Exception e) {
            log.error("发布点击点坐标失败", e);
            return false;
        }
    }

    public boolean publishInitialPose(double x, double y, double z,
                                      double orientationX, double orientationY,
                                      double orientationZ, double orientationW) {
        try {
            PoseWithCovarianceStamped msg = new PoseWithCovarianceStamped();

            std_msgs.msg.Header header = new std_msgs.msg.Header();
            header.setFrameId("map");
            msg.setHeader(header);

            PoseWithCovariance poseWithCovariance = new PoseWithCovariance();
            Pose pose = new Pose();

            Point position = new Point();
            position.setX(x);
            position.setY(y);
            position.setZ(z);
            pose.setPosition(position);

            Quaternion orientation = new Quaternion();
            orientation.setX(orientationX);
            orientation.setY(orientationY);
            orientation.setZ(orientationZ);
            orientation.setW(orientationW);
            pose.setOrientation(orientation);

            poseWithCovariance.setPose(pose);

            // 修复协方差数组长度必须为36
            double[] covariance = new double[36];
            java.util.Arrays.fill(covariance, 0.0);

            covariance[0] = 0.25;
            covariance[7] = 0.25;
            covariance[14] = 0.0;
            covariance[21] = 0.068;
            covariance[28] = 0.068;
            covariance[35] = 0.068;

            poseWithCovariance.setCovariance(covariance);
            msg.setPose(poseWithCovariance);

            initialPosePublisher.publish(msg);

            log.info("发布初始位姿信息成功: 位置[{}, {}, {}], 方向[{}, {}, {}, {}]",
                    x, y, z, orientationX, orientationY, orientationZ, orientationW);
            return true;

        } catch (Exception e) {
            log.error("发布初始位姿信息失败", e);
            return false;
        }
    }
    
    /**
     * 发布速度控制命令
     * @param linearX 线速度X
     * @param linearY 线速度Y  
     * @param linearZ 线速度Z
     * @param angularX 角速度X
     * @param angularY 角速度Y
     * @param angularZ 角速度Z
     * @return 发布是否成功
     */
    public static boolean publishCmdVel(double linearX, double linearY, double linearZ,
                               double angularX, double angularY, double angularZ) {
        try {
            Twist msg = new Twist();
            
            // 设置线速度
            Vector3 linear = new Vector3();
            linear.setX(linearX);
            linear.setY(linearY);
            linear.setZ(linearZ);
            msg.setLinear(linear);
            
            // 设置角速度
            Vector3 angular = new Vector3();
            angular.setX(angularX);
            angular.setY(angularY);
            angular.setZ(angularZ);
            msg.setAngular(angular);
            
            cmdVelPub.publish(msg);
            
            log.info("发布速度控制命令 - 线速度[{}, {}, {}], 角速度[{}, {}, {}]", 
                    linearX, linearY, linearZ, angularX, angularY, angularZ);
            return true;
            
        } catch (Exception e) {
            log.error("发布速度控制命令失败", e);
            return false;
        }
    }

    // 其他发布方法保持不变
    public void publishSpinAction(float value) {
        try {
            Float32 msg = new Float32();
            msg.setData(value);
            spinActionPub.publish(msg);
            log.debug("发布旋转动作: {}", value);
        } catch (Exception e) {
            log.error("发布旋转动作失败", e);
        }
    }

    public static boolean publishPathPoint(double[] points) {
        try {
            Float64MultiArray msg = new Float64MultiArray();
            msg.setData(points);
            pathPointPub.publish(msg);
            log.debug("发布路径点: {}", java.util.Arrays.toString(points));
            return true;
        } catch (Exception e) {
            log.error("发布路径点失败", e);
            return false;
        }
    }

    public boolean publishzThreshold(double[] z) {
        try {
            Float64MultiArray msg = new Float64MultiArray();
            msg.setData(z);
            zThresholdPublisher.publish(msg);
            log.debug("发布Z阈值: {}", java.util.Arrays.toString(z));
            return true;
        } catch (Exception e) {
            log.error("发布Z阈值失败", e);
            return false;
        }
    }

    public void publishCarRunStar(byte value) {
        try {
            UInt8 msg = new UInt8();
            msg.setData(value);
            carRunStarPub.publish(msg);
            log.debug("发布车辆运行状态: {}", value);
        } catch (Exception e) {
            log.error("发布车辆运行状态失败", e);
        }
    }

    public static void publishRemoteCtrlAutomaticSwitch(byte value) {
        try {
            UInt8 msg = new UInt8();
            msg.setData(value);
            RemoteCtrlAutomaticSwitchPub.publish(msg);
            log.debug("发布遥控/自动切换: {}", value);
        } catch (Exception e) {
            log.error("发布遥控/自动切换失败", e);
        }
    }

    public void publishLaneChangeRule(byte value) {
        try {
            UInt8 msg = new UInt8();
            msg.setData(value);
            laneChangeRulePub.publish(msg);
            log.debug("发布车道变换规则: {}", value);
        } catch (Exception e) {
            log.error("发布车道变换规则失败", e);
        }
    }

    public void publishPlanStart(int value) {
        try {
            Int32 msg = new Int32();
            msg.setData(value);
            planstarPublisher.publish(msg);
            log.info("发布规划开始: {}", value);
        } catch (Exception e) {
            log.error("发布规划开始失败", e);
        }
    }

    public boolean publishBuildMap(String value) {
        try {
            if (buildmapPublisher == null) {
                log.error("建图发布器尚未初始化，无法发布信号: {}", value);
                return false;
            }
            std_msgs.msg.String msg = new std_msgs.msg.String();
            msg.setData(value);
            buildmapPublisher.publish(msg);
            log.info("发布建图信号: {}", value);
            return true;
        } catch (Exception e) {
            log.error("发布建图信号失败", e);
            return false;
        }
    }

    public void publishMapPath(String path) {
        try {
            std_msgs.msg.String msg = new std_msgs.msg.String();
            msg.setData(path);
            mapPathPublisher.publish(msg);
            log.info("发布地图路径: {}", path);
        } catch (Exception e) {
            log.error("发布地图路径失败", e);
        }
    }

    public void publishMapPathNav(String path) {
        try {
            std_msgs.msg.String msg = new std_msgs.msg.String();
            msg.setData(path);
            mapPathNavPublisher.publish(msg);
            log.info("发布导航地图路径: {}", path);
        } catch (Exception e) {
            log.error("发布导航地图路径失败", e);
        }
    }

    public void publishPlanSave(int value) {
        try {
            Int32 msg = new Int32();
            msg.setData(value);
            plansavePublisher.publish(msg);
            log.info("发布路径保存: {}", value);
        } catch (Exception e) {
            log.error("发布路径保存失败", e);
        }
    }

    public void publishTerminate(int value) {
        try {
            Int32 msg = new Int32();
            msg.setData(value);
            terminatePublisher.publish(msg);
            log.info("发布规划取消: {}", value);
        } catch (Exception e) {
            log.error("发布规划取消失败", e);
        }
    }

    public void publishrouteName(String value) {
        try {
            std_msgs.msg.String msg = new std_msgs.msg.String();
            msg.setData(value);
            routeNamePublisher.publish(msg);
            log.info("发布路线名称: {}", value);
        } catch (Exception e) {
            log.error("发布路线名称失败", e);
        }
    }
    
    public void publishpcdFolder(String value) {
        try {
            std_msgs.msg.String msg = new std_msgs.msg.String();
            msg.setData(value);
            pcdfolderPublisher.publish(msg);
            log.info("发布路线名称: {}", value);
        } catch (Exception e) {
            log.error("发布路线名称失败", e);
        }
    }
    
    public void publishmapFolder(String value) {
        try {
            std_msgs.msg.String msg = new std_msgs.msg.String();
            msg.setData(value);
            mapfolderPublisher.publish(msg);
            log.info("发布路线名称: {}", value);
        } catch (Exception e) {
            log.error("发布路线名称失败", e);
        }
    }

    public void publishmapCoverage(String value) {
        try {
            std_msgs.msg.String msg = new std_msgs.msg.String();
            msg.setData(value);
            mapCoveragePublisher.publish(msg);
            log.info("发布地图覆盖名称: {}", value);
        } catch (Exception e) {
            log.error("发布地图覆盖名称失败", e);
        }
    }

    /**
     * 发布地图原点变换信息（2D Goal Pose）
     * 功能: 用于更新地图坐标系原点位置和方向
     */
    public boolean publishPgmHomeTf(double x, double y, double z,
                                    double orientationX, double orientationY,
                                    double orientationZ, double orientationW) {
        try {
            PoseStamped msg = new PoseStamped();

            // 设置header - 使用当前时间戳
            std_msgs.msg.Header header = new std_msgs.msg.Header();
            header.setFrameId("map");
            // 不设置时间戳，ROS2会自动填充当前时间
            msg.setHeader(header);

            // 设置位
            Pose pose = new Pose();

            // 设置位置
            Point position = new Point();
            position.setX(x);
            position.setY(y);
            position.setZ(z);
            pose.setPosition(position);

            // 设置方向
            Quaternion orientation = new Quaternion();
            orientation.setX(orientationX);
            orientation.setY(orientationY);
            orientation.setZ(orientationZ);
            orientation.setW(orientationW);
            pose.setOrientation(orientation);

            msg.setPose(pose);

            pgmHomeTfPublisher.publish(msg);

            log.info("发布地图原点变换信息 - /pgm_home_tf: 位置[{}, {}, {}], 方向[{}, {}, {}, {}]",
                    x, y, z, orientationX, orientationY, orientationZ, orientationW);
            return true;

        } catch (Exception e) {
            log.error("发布地图原点变换信息失败", e);
            return false;
        }
    }

    @PreDestroy
    @Override
    public void stop() {
        running.set(false);

        try {
            scheduler.shutdown();
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }

            closeAllSubscriptions();
            closeAllPublishers();

            if (node != null) {
                node.dispose();
                node = null;
            }

            if (RCLJava.ok()) {
                RCLJava.shutdown();
            }

            log.info("ROS2通信服务已安全关闭");
        } catch (Exception e) {
            log.error("关闭ROS2通信服务时出错: ", e);
        }
    }

    private void closeAllSubscriptions() {
        List<Subscription<?>> subscriptions = Arrays.asList(
                plcStatusSub, navStationIdSub, routeIdSub,
                goalFinishSub, routeSerialNumberSub, vehiclePoseSub,
                buildMapStatusSub
        );

        for (Subscription<?> sub : subscriptions) {
            if (sub != null) {
                try {
                    sub.dispose();
                } catch (Exception e) {
                    log.warn("关闭订阅器时出错: ", e);
                }
            }
        }
    }

    private void closeAllPublishers() {
        List<Publisher<?>> publishers = Arrays.asList(
                spinActionPub, pathPointPub, pathPointLeftPub, pathPointRightPub,
                carRunStarPub, laneChangeRulePub, webRemoteCtrlDataPub, RemoteCtrlAutomaticSwitchPub,
                vehicleRunStarPublisher, closeRoutePublisher, planstarPublisher, plansavePublisher,
                terminatePublisher, clickedpointPublisher, routeNamePublisher, mapCoveragePublisher,
                pgmHomeTfPublisher, initialPosePublisher, zThresholdPublisher, plcStartPublisher
        );

        for (Publisher<?> pub : publishers) {
            if (pub != null) {
                try {
                    pub.dispose();
                } catch (Exception e) {
                    log.warn("关闭发布器时出错: ", e);
                }
            }
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public void start() {
        // Spring Lifecycle start
    }

    public boolean isRos2Connected() {
        return running.get() && RCLJava.ok() && node != null;
    }
}
