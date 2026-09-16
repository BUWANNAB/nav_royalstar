package com.ant.robot.controller;

import com.ant.robot.utils.MapPaths;

import com.ant.robot.common.aop.LogAnnotation;
import com.ant.robot.common.response.BaseResponse;
import com.ant.robot.common.response.ResultUtils;
import com.ant.robot.model.domain.Param;
import com.ant.robot.model.request.PlanAreaRequest;
import com.ant.robot.model.request.PointRequest;
import com.ant.robot.model.request.RecordingRequest;
import com.ant.robot.service.ROS2CommunicationService;
import com.ant.robot.service.ParamService;
import com.ant.robot.service.RecordingService;
import jakarta.annotation.Resource;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Slf4j
@RestController
@RequestMapping("/ros2")
public class ROS2Controller {

    private static final int PATH_POINT_FIELD_COUNT = 9;
    private static final int PATH_POINT_HEADER_SIZE = 1;

    @org.springframework.beans.factory.annotation.Value("${pcd.file.path}")
    private String pcdFilePath;

    @org.springframework.beans.factory.annotation.Value("${pgm.root.path}")
    private String pgmRootPath;

    private final ROS2CommunicationService ros2Service;

    @Resource
    private RouteController routeController;

    @Resource
    private RecordingService recordingService;

    @Resource
    private ParamService paramService;

    private Map<String, Process> rosProcesses = new ConcurrentHashMap<>();

    String MapName;

    public ROS2Controller(ROS2CommunicationService ros2Service) {
        this.ros2Service = ros2Service;
        ros2Service.setMessageCallback(message -> {
            System.out.println("收到ROS2消息: " + message.toString());
        });
    }

    /**
     * 车辆控制接口
     */
    @LogAnnotation(title = "ROS2", content = "车辆控制")
    @Transactional(rollbackFor = Exception.class)
    @PostMapping("/vehicle/{action}")
    public BaseResponse<Boolean> vehicleControl(@PathVariable("action") Long action) {
        try {
            log.info("收到车辆控制请求，动作: {}", action);

            if (action == null) {
                return ResultUtils.error(400, "动作参数不能为空");
            }

            boolean success = false;
            String actionName = "";

            switch (action.intValue()) {
                case 0: // 启动
                    success = ros2Service.publishVehicleStartSignal();
                    actionName = "启动";
                    break;
                case 1: // 暂停
                    success = ros2Service.publishVehiclePauseSignal();
                    actionName = "暂停";
                    break;
                case 2: // 任务取消
                    success = ros2Service.publishTaskCancelSignal();
                    actionName = "任务取消";
                    break;
                default:
                    log.warn("不支持的动作类型: {}", action);
                    return ResultUtils.error(400, "不支持的动作类型: " + action);
            }

            if (success) {
                log.info("车辆{}指令发布成功", actionName);
                return ResultUtils.success(true);
            } else {
                log.error("车辆{}指令发布失败", actionName);
                return ResultUtils.error(500, "车辆" + actionName + "指令发布失败");
            }

        } catch (Exception e) {
            log.error("车辆控制处理异常", e);
            return ResultUtils.error(500, "车辆控制处理异常: " + e.getMessage());
        }
    }

    /**
     * 规划区域接口
     */
    @LogAnnotation(title = "ROS2", content = "规划区域")
    @Transactional(rollbackFor = Exception.class)
    @PostMapping("/planstart")
    public BaseResponse<Boolean> planArea(@RequestBody PlanAreaRequest request) {
        try {
            log.info("收到规划区域请求: planMode={}, drawArea点数量={}",
                    request.getPlanMode(),
                    request.getDrawArea() != null ? request.getDrawArea().size() : 0);

            // 参数验证
            if (request.getPlanMode() == null) {
                return ResultUtils.error(400, "规划模式不能为空");
            }

            if (request.getDrawArea() == null || request.getDrawArea().isEmpty()) {
                return ResultUtils.error(400, "绘制区域不能为空");
            }

            // 验证每个点的坐标数据
            for (int i = 0; i < request.getDrawArea().size(); i++) {
                List<Double> point = request.getDrawArea().get(i);
                if (point == null || point.size() < 2) {
                    return ResultUtils.error(400, "第" + (i + 1) + "个点坐标数据不完整");
                }
                if (point.get(0) == null || point.get(1) == null) {
                    return ResultUtils.error(400, "第" + (i + 1) + "个点坐标值不能为空");
                }
            }

            // 发布规划模式和点数据
            ros2Service.publishPlanStart(request.getPlanMode());
            log.info("规划模式发布成功: planMode={}", request.getPlanMode());

            for (int i = 0; i < request.getDrawArea().size(); i++) {
                List<Double> point = request.getDrawArea().get(i);
                double x = point.get(0);
                double y = point.get(1);
                double z = point.size() >= 3 ? point.get(2) : 0.0;

                boolean pointSuccess = ros2Service.publishClickedPoint(x, y, z);
                if (!pointSuccess) {
                    log.error("发布第{}个点失败: x={}, y={}", i + 1, x, y);
                    return ResultUtils.error(500, "发布第" + (i + 1) + "个点失败");
                }

                log.debug("成功发布第{}个点: x={}, y={}, z={}", i + 1, x, y, z);

                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("发布点延迟被中断");
                    return ResultUtils.error(500, "发布过程被中断");
                }
            }

            // 发布第一个点闭合区域
            List<Double> firstPoint = request.getDrawArea().get(0);
            double firstX = firstPoint.get(0);
            double firstY = firstPoint.get(1);
            double firstZ = firstPoint.size() >= 3 ? firstPoint.get(2) : 0.0;

            boolean lastPointSuccess = ros2Service.publishClickedPoint(firstX, firstY, firstZ);
            if (!lastPointSuccess) {
                log.error("发布最后一个点(第一个点的重复)失败");
                return ResultUtils.error(500, "发布最后一个点失败");
            }

            log.info("规划区域数据发布完成: planMode={}, 共发布{}个点",
                    request.getPlanMode(), request.getDrawArea().size() + 1);

            return ResultUtils.success(true);

        } catch (Exception e) {
            log.error("规划区域处理异常", e);
            return ResultUtils.error(500, "规划区域处理异常: " + e.getMessage());
        }
    }

    /**
     * 模式控制接口
     */
    @LogAnnotation(title = "ROS2", content = "模式控制")
    @Transactional(rollbackFor = Exception.class)
    @PostMapping("/mode/{switch}")
    public BaseResponse<Boolean> switchMode(@PathVariable("switch") int modeSwitch) {
        try {
            if (modeSwitch != 0 && modeSwitch != 1) {
                log.error("错误：无效的模式参数，只能是0或1");
                return ResultUtils.error(400, "无效的模式参数，只能是0(自动)或1(手动)");
            }

            String modeName = (modeSwitch == 0) ? "自动模式" : "手动模式";
            log.info("正在切换到: {}", modeName);

            ros2Service.publishRemoteCtrlAutomaticSwitch((byte) modeSwitch);
            return ResultUtils.success(true);

        } catch (Exception e) {
            log.error("模式切换异常", e);
            return ResultUtils.error(500, "模式切换异常: " + e.getMessage());
        }
    }

    /**
     * 全覆盖路径规划开始接口
     */
    @LogAnnotation(title = "ROS2", content = "全覆盖路径规划开始")
    @Transactional(rollbackFor = Exception.class)
    @PostMapping("/planstart2/{mode}")
    public BaseResponse<Boolean> startPlan(@PathVariable("mode") int modeSwitch) {
        try {
            ros2Service.publishPlanStart(modeSwitch);
            log.info("开始路径规划，模式: {}", modeSwitch);
            return ResultUtils.success(true);
        } catch (Exception e) {
            log.error("开始路径规划异常", e);
            return ResultUtils.error(500, "开始路径规划异常: " + e.getMessage());
        }
    }

    /**
     * 路线保存接口
     */
    @LogAnnotation(title = "ROS2", content = "全覆盖路线保存")
    @Transactional(rollbackFor = Exception.class)
    @PostMapping("/saveplan/{routeName}/{mapcoverage}")
    public BaseResponse<Boolean> savePlan(
            @PathVariable("routeName") String routeName,
            @PathVariable("mapcoverage") String mapCoverage) {

        try {
            log.info("收到路线保存请求: routeName={}, mapCoverage={}", routeName, mapCoverage);

            if (routeName == null || routeName.trim().isEmpty()) {
                return ResultUtils.error(400, "路线名不能为空");
            }

            if (mapCoverage == null || mapCoverage.trim().isEmpty()) {
                return ResultUtils.error(400, "地图覆盖名称不能为空");
            }

            ros2Service.publishrouteName(routeName);
            ros2Service.publishmapCoverage(mapCoverage);
            ros2Service.publishPlanSave(1);

            log.info("路线保存成功: routeName={}, mapCoverage={}", routeName, mapCoverage);
            return ResultUtils.success(true);

        } catch (Exception e) {
            log.error("路线保存处理异常", e);
            return ResultUtils.error(500, "路线保存失败: " + e.getMessage());
        }
    }

    /**
     * 开始轨迹录制
     */
    @LogAnnotation(title = "ROS2", content = "开始轨迹录制")
    @Transactional(rollbackFor = Exception.class)
    @PostMapping("/recording/start")
    public BaseResponse<Boolean> startRecording(@RequestBody RecordingRequest request) {
        try {
            log.info("收到开始录制请求: distance={}, mapName={}",
                    request.getDistance(), request.getMapName());

            if (request.getDistance() == null || request.getDistance() < 0.1) {
                return ResultUtils.error(400, "录制间隔距离不能小于0.1米");
            }
            if (request.getMapName() == null || request.getMapName().trim().isEmpty()) {
                MapName = "None";
            } else {
                MapName = request.getMapName();
            }

            
            boolean result = recordingService.startRecording(
                    request.getDistance(),
                    request.getMapName()
            );

            if (result) {
                log.info("轨迹录制开始成功");
                return ResultUtils.success(true);
            } else {
                log.warn("轨迹录制开始失败：已有录制任务正在进行");
                return ResultUtils.error(400, "已有录制任务正在进行");
            }

        } catch (Exception e) {
            log.error("开始录制处理异常", e);
            return ResultUtils.error(500, "开始录制处理异常: " + e.getMessage());
        }
    }

    /**
     * 结束录制并保存路线
     */
    @LogAnnotation(title = "ROS2", content = "结束录制并保存路线")
    @Transactional(rollbackFor = Exception.class)
    @PostMapping("/recording/save")
    public BaseResponse<Boolean> saveRecording(@RequestBody RecordingRequest request) {
        try {
            log.info("收到结束录制请求: routeName={}", request.getRouteName());

            if (request.getRouteName() == null || request.getRouteName().trim().isEmpty()) {
                return ResultUtils.error(400, "路线名称不能为空");
            }

            boolean result = recordingService.stopAndSaveRecording(
                    request.getRouteName(),
                    MapName
            );

            if (result) {
                log.info("轨迹录制保存成功");
                return ResultUtils.success(true);
            } else {
                log.warn("轨迹录制保存失败：无录制任务或点数量不足或保存失败");
                return ResultUtils.error(400, "录制保存失败：无录制任务或点数量不足或保存失败");
            }

        } catch (Exception e) {
            log.error("保存录制处理异常", e);
            return ResultUtils.error(500, "保存录制处理异常: " + e.getMessage());
        }
    }

    /**
     * 取消录制
     */
    @LogAnnotation(title = "ROS2", content = "取消录制")
    @Transactional(rollbackFor = Exception.class)
    @PostMapping("/recording/cancel")
    public BaseResponse<Boolean> cancelRecording() {
        try {
            boolean result = recordingService.cancelRecording();

            if (result) {
                log.info("录制取消成功");
                return ResultUtils.success(true);
            } else {
                log.warn("取消录制失败：无录制任务");
                return ResultUtils.error(400, "无录制任务可取消");
            }
        } catch (Exception e) {
            log.error("取消录制异常", e);
            return ResultUtils.error(500, "取消录制异常: " + e.getMessage());
        }
    }

    /**
     * plc启动
     */
    @LogAnnotation(title = "ROS2", content = "plc启动")
    @Transactional(rollbackFor = Exception.class)
    @PostMapping("/plcstart")
    public BaseResponse<Boolean> starPlc() {
        try {
            boolean result = ros2Service.plcStartPublisherSignal();

            if (result) {
                log.info("plc启动成功");
                return ResultUtils.success(true);
            } else {
                log.warn("plc启动失败");
                return ResultUtils.error(400, "plc启动");
            }
        } catch (Exception e) {
            log.error("plc启动", e);
            return ResultUtils.error(500, "plc启动异常: " + e.getMessage());
        }
    }

    /**
     * 设置初始位姿接口
     */
    @LogAnnotation(title = "ROS2", content = "设置初始位姿")
    @Transactional(rollbackFor = Exception.class)
    @PostMapping("/initial_pose")
    public BaseResponse<Boolean> setInitialPose(@RequestBody InitialPoseRequest request) {
        try {
            log.info("收到初始位姿设置请求: {}", request.toString());

            if (request.getPose() == null || request.getPose().getPose() == null) {
                return ResultUtils.error(400, "位姿数据不能为空");
            }

            InitialPoseRequest.PoseDetail poseDetail = request.getPose().getPose();
            InitialPoseRequest.Position position = poseDetail.getPosition();
            InitialPoseRequest.Orientation orientation = poseDetail.getOrientation();

            if (position == null || position.getX() == null || position.getY() == null) {
                return ResultUtils.error(400, "位置坐标x和y不能为空");
            }

            if (orientation == null || orientation.getZ() == null || orientation.getW() == null) {
                return ResultUtils.error(400, "方向四元数z和w不能为空");
            }

            double x = position.getX();
            double y = position.getY();
            double z = position.getZ() != null ? position.getZ() : 0.0;

            double orientationX = orientation.getX() != null ? orientation.getX() : 0.0;
            double orientationY = orientation.getY() != null ? orientation.getY() : 0.0;
            double orientationZ = orientation.getZ();
            double orientationW = orientation.getW();

            double norm = Math.sqrt(orientationX * orientationX + orientationY * orientationY +
                    orientationZ * orientationZ + orientationW * orientationW);
            if (Math.abs(norm - 1.0) > 0.001) {
                log.warn("四元数不是单位四元数，模长为: {}", norm);
            }

            boolean success = ros2Service.publishInitialPose(
                    x, y, z, orientationX, orientationY, orientationZ, orientationW);

            if (success) {
                log.info("初始位姿设置成功: 位置[{}, {}, {}], 方向[{}, {}, {}, {}]",
                        x, y, z, orientationX, orientationY, orientationZ, orientationW);
                return ResultUtils.success(true);
            } else {
                log.error("初始位姿设置失败");
                return ResultUtils.error(500, "初始位姿设置失败");
            }

        } catch (Exception e) {
            log.error("设置初始位姿处理异常", e);
            return ResultUtils.error(500, "设置初始位姿处理异常: " + e.getMessage());
        }
    }

    /**
     * 规划取消接口
     */
    @LogAnnotation(title = "ROS2", content = "规划取消")
    @Transactional(rollbackFor = Exception.class)
    @PostMapping("/terminate/{mode}")
    public BaseResponse<Boolean> terminatePlan(@PathVariable("mode") int modeSwitch) {
        try {
            ros2Service.publishTerminate(modeSwitch);
            log.info("取消规划，模式: {}", modeSwitch);
            return ResultUtils.success(true);
        } catch (Exception e) {
            log.error("取消规划异常", e);
            return ResultUtils.error(500, "取消规划异常: " + e.getMessage());
        }
    }

    /**
     * 路线发布接口
     */
    @LogAnnotation(title = "ROS2", content = "路线发布")
    @Transactional(rollbackFor = Exception.class)
    @PostMapping("/publishpathpoint/{id}")
    public BaseResponse<Boolean> publishPathPoint(@PathVariable("id") Long routeId) {
        try {
            log.info("收到路线发布请求: routeId={}", routeId);

            if (routeId == null || routeId <= 0) {
                return ResultUtils.error(400, "路线ID不能为空且必须大于0");
            }

            BaseResponse<List<Object>> routeResponse = routeController.queryRouteDetailData(routeId);

            ros2Service.routeIdPublisherSignal(routeId);

            if (routeResponse == null ) {
                log.error("查询路线数据失败: routeId={}, 响应: {}", routeId, routeResponse);
                return ResultUtils.error(500, "查询路线数据失败: " +
                        (routeResponse != null ? routeResponse.getMessage() : "响应为空"));
            }

            List<Object> routeData = routeResponse.getData();
            if (routeData == null || routeData.isEmpty()) {
                log.error("路线数据为空: routeId={}", routeId);
                return ResultUtils.error(404, "未找到对应的路线数据");
            }

            double[] pathPoints;
            try {
                pathPoints = routeData.stream()
                        .mapToDouble(point -> {
                            if (point instanceof Number) {
                                return ((Number) point).doubleValue();
                            } else {
                                throw new IllegalArgumentException("路线数据包含非数字类型: " + point.getClass().getSimpleName());
                            }
                        })
                        .toArray();
            } catch (Exception e) {
                log.error("路线数据格式错误: routeId={}, 错误: {}", routeId, e.getMessage());
                return ResultUtils.error(400, "路线数据格式错误: " + e.getMessage());
            }

            // 硬件协议：data[0] 为点数，之后每个点固定 9 个字段。
            if (pathPoints.length < PATH_POINT_HEADER_SIZE
                    || (pathPoints.length - PATH_POINT_HEADER_SIZE) % PATH_POINT_FIELD_COUNT != 0) {
                log.error("路线数据长度不符合 path_point 协议: routeId={}, dataSize={}",
                        routeId, pathPoints.length);
                return ResultUtils.error(400, "路线数据长度不符合硬件协议，应为 1 + 9×N");
            }

            int pointCount = (pathPoints.length - PATH_POINT_HEADER_SIZE) / PATH_POINT_FIELD_COUNT;
            if (pointCount < 2) {
                log.error("路线数据点数量不足: routeId={}, pointCount={}", routeId, pointCount);
                return ResultUtils.error(400, "路线数据点数量不足，至少需要2个点");
            }

            if (Math.abs(pathPoints[0] - pointCount) > 1e-9) {
                log.error("路线头部点数与实际点数不一致: routeId={}, headerCount={}, actualCount={}",
                        routeId, pathPoints[0], pointCount);
                return ResultUtils.error(400, "路线头部点数与实际路径点数量不一致");
            }

            boolean publishSuccess = ros2Service.publishPathPoint(pathPoints);

            if (publishSuccess) {
                log.info("路线发布成功: routeId={}, 包含{}个坐标值({}个点)",
                        routeId, pathPoints.length, pointCount);
                return ResultUtils.success(true);
            } else {
                log.error("路线发布失败: routeId={}", routeId);
                return ResultUtils.error(500, "路线发布失败");
            }

        } catch (Exception e) {
            log.error("路线发布处理异常: routeId={}", routeId, e);
            return ResultUtils.error(500, "路线发布处理异常: " + e.getMessage());
        }
    }

    /**
     * 发布点击点坐标接口
     */
    @LogAnnotation(title = "ROS2", content = "发布点击点坐标")
    @Transactional(rollbackFor = Exception.class)
    @PostMapping("/point/click")
    public BaseResponse<Boolean> publishClickedPoint(@RequestBody PointRequest request) {
        try {
            log.info("收到点击点坐标发布请求: {}", request.toString());

            if (request.getX() == null || request.getY() == null) {
                return ResultUtils.error(400, "x和y坐标不能为空");
            }

            if (request.getX().isNaN() || request.getY().isNaN()) {
                return ResultUtils.error(400, "坐标值必须为有效数字");
            }

            double z = request.getZ() != null ? request.getZ() : 0.0;

            boolean success = ros2Service.publishClickedPoint(
                    request.getX(), request.getY(), z);

            if (success) {
                log.info("点击点坐标发布成功: x={}, y={}, z={}",
                        request.getX(), request.getY(), z);
                return ResultUtils.success(true);
            } else {
                log.error("点击点坐标发布失败");
                return ResultUtils.error(500, "点击点坐标发布失败");
            }

        } catch (Exception e) {
            log.error("发布点击点坐标处理异常", e);
            return ResultUtils.error(500, "发布点击点坐标处理异常: " + e.getMessage());
        }
    }

    /**
     * 开始建图
     */
    @LogAnnotation(title = "ROS2", content = "开始建图")
    @Transactional(rollbackFor = Exception.class)
    @PostMapping("/run_mapping")


    public BaseResponse<Boolean> runMappingCommands() {
        try {
            log.info("开始执行建图命令序列");
            ros2Service.clearBuildMapStatus();
            if (!ros2Service.publishBuildMap("start")) {
                return ResultUtils.error(503, "建图发布器未就绪");
            }

            String status = ros2Service.waitForBuildMapReady(60000);
            if (!"map_data_ready".equals(status)) {
                log.warn("建图未达到可录制状态，当前状态: {}", status);
                return ResultUtils.error(409, "建图未就绪，当前状态: " + status);
            }

            log.info("建图已进入可录制状态");
            return ResultUtils.success(true);
        } catch (Exception e) {
            log.error("执行建图命令异常", e);
            return ResultUtils.error(500, "执行建图命令异常: " + e.getMessage());
        }
    }


    /**
     * 结束建图
     */
    @LogAnnotation(title = "ROS2", content = "结束建图")
    @Transactional(rollbackFor = Exception.class)
    @PostMapping("/save_mapping/{mapname}")
    public BaseResponse<Boolean> saveMappingCommands(@PathVariable("mapname") String mapname) {
        try {

            if (mapname == null || mapname.trim().isEmpty()) {
                mapname = "nan";
                log.warn("传入的mapname为空，使用默认值: {}", mapname);
            }

            // 判断是否为取消录制信号
            if ("stopbuildmap".equals(mapname)) {
                log.info("收到取消录制信号，发布停止话题");
                return ros2Service.publishBuildMap("stop")
                        ? ResultUtils.success(true)
                        : ResultUtils.error(503, "停止建图信号发布失败");
            }

            log.info("保存地图: {}", mapname);
            Path mapFile = MapPaths.globalMap(pcdFilePath, mapname.trim());
            ros2Service.clearBuildMapStatus();
            if (!ros2Service.publishBuildMap(mapname.trim())) {
                return ResultUtils.error(503, "保存地图信号发布失败");
            }

            String status = ros2Service.waitForBuildMapSave(330000);
            if (status == null || !status.startsWith("saved:")) {
                log.warn("地图保存未完成，地图: {}, 当前状态: {}", mapname, status);
                return ResultUtils.error(409, "地图保存失败，当前状态: " + status);
            }

            if (!waitForMapFile(mapFile, 10000)) {
                log.error("建图节点已报告保存成功，但文件未生成: {}", mapFile);
                return ResultUtils.error(500, "地图文件未生成: " + mapname);
            }

            log.info("地图 {} 已保存: {}", mapname, mapFile);
            return ResultUtils.success(true);

        } catch (Exception e) {
            log.error("保存地图异常", e);
            return ResultUtils.error(500, "保存地图异常: " + e.getMessage());
        }
    }

    private boolean waitForMapFile(Path mapFile, long timeoutMs) {
        long deadline = System.currentTimeMillis() + Math.max(timeoutMs, 1L);
        while (System.currentTimeMillis() < deadline) {
            try {
                if (Files.isRegularFile(mapFile) && Files.size(mapFile) > 0) {
                    return true;
                }
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            } catch (IOException e) {
                log.debug("等待地图文件时暂时无法读取: {}", mapFile, e);
            }
        }
        return false;
    }

    @GetMapping("/mapping_status")
    public BaseResponse<String> getMappingStatus() {
        return ResultUtils.success(ros2Service.getBuildMapStatus());
    }

    /**
     * 发布PCD文件夹名称
     */
    @LogAnnotation(title = "ROS2", content = "发布PCD文件夹名称")
    @PostMapping("/pcdfolder/{foldername}")
    public BaseResponse<Boolean> publishPcdFolder(@PathVariable("foldername") String foldername) {
        try {
            // 检查foldername是否为空
            if (foldername == null || foldername.trim().isEmpty()) {
                return ResultUtils.error(400, "foldername不能为空");
            }
            
            log.info("接收到PCD文件夹名称: {}", foldername);
            
            // 调用现有的ros2Service发布方法
            ros2Service.publishpcdFolder(foldername);
            
            log.info("PCD文件夹名称发布成功: {}", foldername);
            return ResultUtils.success(true);
            
        } catch (Exception e) {
            log.error("发布PCD文件夹名称异常", e);
            return ResultUtils.error(500, "发布PCD文件夹名称异常: " + e.getMessage());
        }
    }
    
    /**
     * 更新地图路径接口
     */
    @LogAnnotation(title = "ROS2", content = "更新地图路径")
    @PostMapping("/update_map_path/{pcdfolder}")
    public BaseResponse<Boolean> updateMapPath(@PathVariable("pcdfolder") String pcdFolder) {
        try {
            log.info("收到更新地图路径请求，PCD文件夹: {}", pcdFolder);
            
            // 参数验证
            if (pcdFolder == null || pcdFolder.trim().isEmpty()) {
                return ResultUtils.error(400, "PCD文件夹名称不能为空");
            }
            
            String folderName = pcdFolder.trim();
            if (folderName.isEmpty()) {
                return ResultUtils.error(400, "PCD文件夹名称无效");
            }
            
            // 经 /map_path 热切换定位地图（不写库；持久化请用 /set_nav_map）
            java.nio.file.Path mapPathFile = MapPaths.globalMap(pcdFilePath, folderName);
            if (!Files.isRegularFile(mapPathFile)) {
                return ResultUtils.error(400, "PCD地图不存在: " + folderName);
            }
            String newMapPath = mapPathFile.toString();
            ros2Service.publishMapPathNav(newMapPath);
            log.info("地图路径已热切换: {}", newMapPath);
            return ResultUtils.success(true);

        } catch (Exception e) {
            log.error("更新地图路径处理异常", e);
            return ResultUtils.error(500, "更新地图路径处理异常: " + e.getMessage());
        }
    }

    /**
     * 设为导航地图
     */
    @LogAnnotation(title = "ROS2", content = "设为导航地图")
    @PostMapping("/set_nav_map")
    public BaseResponse<Boolean> setNavMap(@RequestBody Map<String, String> request) {
        try {
            String fileName = request.get("fileName");
            if (fileName == null || fileName.trim().isEmpty()) {
                return ResultUtils.error(400, "文件名不能为空");
            }
            fileName = fileName.trim();
            java.nio.file.Path mapPathFile = MapPaths.globalMap(pcdFilePath, fileName);
            if (!Files.isRegularFile(mapPathFile)) {
                return ResultUtils.error(400, "PCD地图不存在: " + fileName);
            }

            java.nio.file.Path mapRoot = Paths.get(pgmRootPath).toAbsolutePath().normalize();
            java.nio.file.Path settingDir = mapRoot.resolve(fileName).resolve("setting").normalize();
            if (!settingDir.startsWith(mapRoot)
                    || !Files.isRegularFile(settingDir.resolve("map.pgm"))
                    || !Files.isRegularFile(settingDir.resolve("map.yaml"))) {
                return ResultUtils.error(400, "请先生成并保存同名2D地图: " + fileName);
            }

            String mapPath = mapPathFile.toString();
            ros2Service.publishMapPathNav(mapPath);

            // 更新数据库中的导航地图PCD名称
            Param param = paramService.getById(1L);
            if (param == null) {
                param = new Param();
                param.setId(1L);
            }
            param.setNavMapPcdName(fileName);
            paramService.saveOrUpdate(param);

            log.info("设为导航地图成功: {}", mapPath);
            return ResultUtils.success(true);
        } catch (Exception e) {
            log.error("设为导航地图异常", e);
            return ResultUtils.error(500, "设为导航地图异常: " + e.getMessage());
        }
    }

    /**
     * 获取导航地图PCD名称
     */
    @LogAnnotation(title = "ROS2", content = "获取导航地图PCD名称")
    @GetMapping("/get_nav_map")
    public BaseResponse<String> getNavMap() {
        try {
            Param param = paramService.getById(1L);
            if (param == null) {
                return ResultUtils.success(null);
            }
            return ResultUtils.success(param.getNavMapPcdName());
        } catch (Exception e) {
            log.error("获取导航地图PCD名称异常", e);
            return ResultUtils.error(500, "获取导航地图PCD名称异常: " + e.getMessage(), "");
        }
    }

    /**
     * 执行Linux命令的辅助方法
     */
    private void executeCommand(String command) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder("bash", "-c", command);
        pb.redirectErrorStream(true);
        Process process = pb.start();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                log.info("命令输出: {}", line);
            }
        }

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException("命令执行失败，退出码: " + exitCode);
        }
    }

    /**
     * 关闭特定的ROS节点
     */
    private void closeSpecificRosNodes() {
        String[] nodesToClose = {
                "/lio_sam_featureExtraction",
                "/lio_sam_imageProjection",
                "/lio_sam_imuPreintegration",
                "/livox_lidar_publisher"
        };

        for (String nodeName : nodesToClose) {
            closeRosNode(nodeName);
        }
    }

    /**
     * 关闭单个ROS节点
     */
    private void closeRosNode(String nodeName) {
        try {
            log.info("正在关闭ROS节点: {}", nodeName);

            String command = "source ~/blueant_nav_ws/install/setup.bash && ros2 lifecycle set " + nodeName + " shutdown";

            ProcessBuilder pb = new ProcessBuilder("/bin/bash", "-c", command);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            // 读取关闭命令的输出
            Thread outputThread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        log.info("[关闭{}] {}", nodeName, line);
                    }
                } catch (IOException e) {
                    log.error("读取关闭节点{}输出异常", nodeName, e);
                }
            });
            outputThread.start();

            // 等待命令执行完成，最多等待10秒
            boolean exited = process.waitFor(10, TimeUnit.SECONDS);
            if (exited) {
                int exitCode = process.exitValue();
                if (exitCode == 0) {
                    log.info("成功关闭ROS节点: {}", nodeName);
                } else {
                    log.warn("关闭ROS节点{}失败，退出码: {}", nodeName, exitCode);
                }
            } else {
                log.warn("关闭ROS节点{}超时，强制终止", nodeName);
                process.destroyForcibly();

                // 尝试强制关闭
                try {
                    String killCommand = "source ~/blueant_nav_ws/install/setup.bash && ros2 lifecycle set " + nodeName + " cleanup";
                    Process killProcess = new ProcessBuilder("/bin/bash", "-c", killCommand).start();
                    boolean killExited = killProcess.waitFor(5, TimeUnit.SECONDS);
                    if (!killExited) {
                        killProcess.destroyForcibly();
                    }
                    log.info("尝试强制清理节点: {}", nodeName);
                } catch (Exception e) {
                    log.error("强制清理节点{}失败", nodeName, e);
                }
            }

        } catch (Exception e) {
            log.error("关闭ROS节点{}异常", nodeName, e);
        }
    }

    /**
     * 初始位姿请求参数
     */
    @Data
    public static class InitialPoseRequest {
        private PoseData pose;

        @Data
        public static class PoseData {
            private PoseDetail pose;
        }

        @Data
        public static class PoseDetail {
            private Position position;
            private Orientation orientation;
        }

        @Data
        public static class Position {
            private Double x;
            private Double y;
            private Double z;
        }

        @Data
        public static class Orientation {
            private Double x;
            private Double y;
            private Double z;
            private Double w;
        }
    }

    private void startRosNode(String nodeName, String command) {
        try {
            log.info("启动ROS节点: {}", nodeName);

            ProcessBuilder pb = new ProcessBuilder("/bin/bash", "-c", command);
            pb.redirectErrorStream(true);

            Process process = pb.start();
            rosProcesses.put(nodeName, process);

            // 启动线程读取输出，避免缓冲区满导致进程阻塞
            new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        log.info("[{}] {}", nodeName, line);
                    }
                } catch (IOException e) {
                    log.error("读取节点{}输出异常", nodeName, e);
                }
            }).start();

            log.info("ROS节点 {} 已启动，PID: {}", nodeName, process.pid());

        } catch (IOException e) {
            log.error("启动ROS节点{}失败", nodeName, e);
            throw new RuntimeException("启动节点失败: " + nodeName, e);
        }
    }

    // 可以添加停止节点的方法
    public BaseResponse<Boolean> stopMappingCommands() {
        try {
            log.info("停止建图节点");

            rosProcesses.forEach((name, process) -> {
                if (process != null && process.isAlive()) {
                    log.info("停止节点: {}", name);
                    process.destroy();
                    try {
                        if (process.waitFor(5, TimeUnit.SECONDS)) {
                            process.destroyForcibly();
                        }
                    } catch (InterruptedException e) {
                        process.destroyForcibly();
                    }
                }
            });

            rosProcesses.clear();
            log.info("所有节点已停止");
            return ResultUtils.success(true);

        } catch (Exception e) {
            log.error("停止建图节点异常", e);
            return ResultUtils.error(500, "停止节点异常: " + e.getMessage());
        }
    }


    @GetMapping("/status")
    public BaseResponse<Boolean> getStatus() {
        try {
            return ResultUtils.success(true);
        } catch (Exception e) {
            log.error("获取状态异常", e);
            return ResultUtils.error(500, "获取状态异常: " + e.getMessage());
        }
    }
}
