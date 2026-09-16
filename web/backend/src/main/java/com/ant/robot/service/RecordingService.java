package com.ant.robot.service;

import com.ant.robot.common.response.BaseResponse;
import com.ant.robot.controller.RouteController;
import com.ant.robot.model.request.RouteAllRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Service
public class RecordingService {

    @Autowired
    private ROS2CommunicationService ros2Service;

    @Autowired
    private RouteController routeController;

    // 录制状态
    private final AtomicBoolean isRecording = new AtomicBoolean(false);
    private final AtomicReference<LocalDateTime> startTime = new AtomicReference<>();
    private final AtomicReference<Double> recordingDistance = new AtomicReference<>();
    private final AtomicReference<String> currentMapName = new AtomicReference<>();

    // 录制的轨迹点
    private final List<Map<String, Object>> recordedPoints = new CopyOnWriteArrayList<>();
    private final Map<String, Object> lastRecordedPoint = new ConcurrentHashMap<>();

    // 格式化时间
    private static final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 开始录制
     * @return true-开始成功, false-已有录制任务存在
     */
    public boolean startRecording(double distance, String mapName) {
        try {
            // 检查是否已经在录制
            if (isRecording.get()) {
                log.warn("录制正在进行中，无法开始新录制");
                return false;
            }

            // 重置状态
            resetRecording();

            // 设置录制参数
            isRecording.set(true);
            startTime.set(LocalDateTime.now());
            recordingDistance.set(distance);
            currentMapName.set(mapName);

            // 设置车辆位姿回调
            ros2Service.setMessageCallback(message -> {
                if (isRecording.get() && message instanceof geometry_msgs.msg.PoseStamped) {
                    handleVehiclePose((geometry_msgs.msg.PoseStamped) message);
                }
            });

            log.info("录制开始成功: distance={}, mapName={}", distance, mapName);
            return true;

        } catch (Exception e) {
            log.error("开始录制失败", e);
            resetRecording();
            throw new RuntimeException("开始录制失败: " + e.getMessage());
        }
    }

    /**
     * 处理车辆位姿消息
     */
    private void handleVehiclePose(geometry_msgs.msg.PoseStamped pose) {
        try {
            if (!isRecording.get()) {
                return;
            }

            // 提取位置信息
            geometry_msgs.msg.Point position = pose.getPose().getPosition();
            double currentX = position.getX();
            double currentY = position.getY();
            double currentZ = position.getZ();

            // 提取方向信息
            geometry_msgs.msg.Quaternion orientation = pose.getPose().getOrientation();
            double orientationX = orientation.getX();
            double orientationY = orientation.getY();
            double orientationZ = orientation.getZ();
            double orientationW = orientation.getW();

            // 获取当前时间
            LocalDateTime currentTime = LocalDateTime.now();

            // 创建当前点信息
            Map<String, Object> currentPoint = new HashMap<>();
            currentPoint.put("x", currentX);
            currentPoint.put("y", currentY);
            currentPoint.put("z", currentZ);
            currentPoint.put("orientationX", orientationX);
            currentPoint.put("orientationY", orientationY);
            currentPoint.put("orientationZ", orientationZ);
            currentPoint.put("orientationW", orientationW);
            currentPoint.put("timestamp", currentTime);

            // 如果是第一个点，直接记录
            if (recordedPoints.isEmpty()) {
                recordedPoints.add(currentPoint);
                lastRecordedPoint.putAll(currentPoint);
                log.debug("记录第一个轨迹点: x={}, y={}", currentX, currentY);
                return;
            }

            // 计算与上一个记录点的距离
            double lastX = (Double) lastRecordedPoint.get("x");
            double lastY = (Double) lastRecordedPoint.get("y");
            double distance = calculateDistance(lastX, lastY, currentX, currentY);

            // 如果距离达到录制间隔，记录新点
            if (distance >= recordingDistance.get()) {
                recordedPoints.add(currentPoint);
                lastRecordedPoint.putAll(currentPoint);
                log.debug("记录轨迹点[{}]: x={}, y={}, 距离={}m",
                        recordedPoints.size(), currentX, currentY, distance);
            }

        } catch (Exception e) {
            log.error("处理车辆位姿消息异常", e);
        }
    }

    /**
     * 计算两点之间的距离
     */
    private double calculateDistance(double x1, double y1, double x2, double y2) {
        return Math.sqrt(Math.pow(x2 - x1, 2) + Math.pow(y2 - y1, 2));
    }

    /**
     * 结束录制并保存路线
     * @return true-保存成功, false-无录制任务/点不足/保存失败
     */
    public boolean stopAndSaveRecording(String routeName, String mapName) {
        try {
            // 检查是否在录制状态
            if (!isRecording.get()) {
                log.warn("没有正在进行的录制任务");
                return false;
            }

            // 停止录制
            isRecording.set(false);
            LocalDateTime endTime = LocalDateTime.now();

            // 检查是否有足够的轨迹点
            if (recordedPoints.size() < 2) {
                log.warn("录制的轨迹点数量不足，至少需要2个点，当前点数: {}", recordedPoints.size());
                resetRecording();
                return false;
            }

            // 构建路线保存请求
            RouteAllRequest routeRequest = buildRouteRequest(routeName, mapName);

            // 调用RouteController保存路线
            BaseResponse<Boolean> saveResult = routeController.saveRoute(routeRequest);

            if (!saveResult.getData()) {
                log.error("保存路线失败: {}", saveResult.getMessage());
                resetRecording();
                return false;
            }

            log.info("录制保存成功: 路线名称={}, 轨迹点数量={}, 结束时间={}",
                    routeName, recordedPoints.size(), endTime.format(formatter));

            // 重置录制状态
            resetRecording();
            return true;

        } catch (Exception e) {
            log.error("保存录制失败", e);
            resetRecording();
            return false;
        }
    }

    /**
     * 构建路线保存请求
     */
    private RouteAllRequest buildRouteRequest(String routeName, String mapName) {
        RouteAllRequest request = new RouteAllRequest();
        request.setRouteName(routeName);
        request.setMapName(mapName);
        request.setMapCoverage("20");
        request.setRoutesource("2");
        request.setSpeed("0.2");

        // 构建站点列表
        List<RouteAllRequest.RouteStation> stations = new ArrayList<>();

        for (int i = 0; i < recordedPoints.size(); i++) {
            Map<String, Object> point = recordedPoints.get(i);

            RouteAllRequest.RouteStation station = new RouteAllRequest.RouteStation();
            station.setStationName(routeName + "_P" + (i + 1));
            station.setPositionX(String.valueOf(point.get("x")));
            station.setPositionY(String.valueOf(point.get("y")));
            station.setPositionZ(String.valueOf(point.get("z")));
            station.setOrientationX(String.valueOf(point.get("orientationX")));
            station.setOrientationY(String.valueOf(point.get("orientationY")));
            station.setOrientationZ(String.valueOf(point.get("orientationZ")));
            station.setOrientationW(String.valueOf(point.get("orientationW")));
            station.setSpeed("1.0");
            station.setDirection("0");
            station.setArea("0");
            station.setAction("0");
            station.setStopTime("0");
            station.setPosition("0");
            station.setStop("0");
            station.setRunmode("0");
            station.setLanechange("0");

            stations.add(station);
        }

        request.setStations(stations);
        return request;
    }

    /**
     * 获取录制状态
     */
    public Map<String, Object> getRecordingStatus() {
        return buildRecordingStatus();
    }

    /**
     * 取消录制
     * @return true-取消成功, false-无录制任务
     */
    public boolean cancelRecording() {
        try {
            if (isRecording.get()) {
                isRecording.set(false);
                resetRecording();
                log.info("录制已取消");
                return true;
            }
            log.warn("没有正在进行的录制任务，无需取消");
            return false;
        } catch (Exception e) {
            log.error("取消录制异常", e);
            return false;
        }
    }

    /**
     * 构建录制状态信息
     */
    private Map<String, Object> buildRecordingStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("isRecording", isRecording.get());
        status.put("recordedPoints", recordedPoints.size());

        if (startTime.get() != null) {
            status.put("startTime", startTime.get().format(formatter));
        }
        if (recordingDistance.get() != null) {
            status.put("distance", recordingDistance.get());
        }
        if (currentMapName.get() != null) {
            status.put("mapName", currentMapName.get());
        }

        status.put("status", isRecording.get() ? "recording" : "stopped");
        return status;
    }

    /**
     * 重置录制状态
     */
    private void resetRecording() {
        isRecording.set(false);
        startTime.set(null);
        recordingDistance.set(null);
        currentMapName.set(null);
        recordedPoints.clear();
        lastRecordedPoint.clear();

        // 移除回调
        ros2Service.setMessageCallback(null);
    }
}