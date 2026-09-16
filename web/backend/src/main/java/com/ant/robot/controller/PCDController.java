package com.ant.robot.controller;

import com.ant.robot.utils.MapPaths;

import com.ant.robot.common.enums.ErrorCode;
import com.ant.robot.common.response.BaseResponse;
import com.ant.robot.common.response.ResultUtils;
import com.ant.robot.model.request.ProcessPcdRequest;
import com.ant.robot.service.ROS2CommunicationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.MalformedURLException;
import java.nio.file.Path;
import java.util.List;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@RestController
@RequestMapping("pcd")
@Slf4j
public class PCDController {

    @Value("${pcd.file.path}")
    private String pcdFilePath;
    
    @jakarta.annotation.Resource
    ROS2CommunicationService ros2CommunicationService;


    /**
     * 获取PCD文件下的文件夹名称列表
     *
     * @return BaseResponse<List<String>> 包含文件夹名称列表的响应结果
     */
    @GetMapping("/list")
    public BaseResponse<List<String>> getPcdFileList() {
        Path dirPath = Paths.get(pcdFilePath);

        // 检查配置的PCD路径是否有效
        if (!Files.exists(dirPath) || !Files.isDirectory(dirPath)) {
            log.error("配置的PCD路径无效或不存在: {}", pcdFilePath);
            return ResultUtils.error(ErrorCode.SYSTEM_ERROR, "配置的PCD路径无效: " + pcdFilePath);
        }

        // 扫描目录并筛选子文件夹
        try (Stream<Path> stream = Files.list(dirPath)) {
            List<String> subdirectories = stream
                    .filter(Files::isDirectory) // 只筛选目录，不筛选文件
                    .map(Path::getFileName)      // 获取目录名
                    .map(Path::toString)         // 转换为字符串
                    .collect(Collectors.toList());

            return ResultUtils.success(subdirectories);

        } catch (IOException e) {
            log.error("扫描PCD目录时发生IO异常", e);
            return ResultUtils.error(ErrorCode.SYSTEM_ERROR, "扫描目录时出错: " + e.getMessage());
        }
    }


    /**
     * 获取指定文件夹下的GlobalMap.pcd文件
     *
     * @param filename 文件夹名称
     * @return ResponseEntity<?> 包含文件资源的响应实体，如果成功则返回文件流，否则返回错误信息
     */
    @GetMapping("/{filename}/GlobalMap")
    public ResponseEntity<?> getPcdFile(@PathVariable String filename) {

        // 构造实际的文件名，将filename作为文件夹名，GlobalMap.pcd作为文件名
        String actualFilename = "GlobalMap.pcd";

        try {
            // 构建文件路径并进行安全检查，防止路径遍历攻击
            Path dirPath = Paths.get(pcdFilePath).toAbsolutePath();
            // 先进入filename文件夹，然后查找GlobalMap.pcd文件
            Path filePath = dirPath.resolve(filename).resolve(actualFilename).toAbsolutePath();

            // 检查文件路径是否在指定目录内，防止路径遍历攻击
            if (!filePath.startsWith(dirPath)) {
                log.warn("检测到路径遍历攻击尝试: {}", filename);
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(ResultUtils.error(ErrorCode.SYSTEM_ERROR, "禁止访问"));
            }

            // 尝试加载文件资源
            Resource resource = new UrlResource(filePath.toUri());

            // 检查文件是否存在且可读，如果满足条件则返回文件
            if (resource.exists() && resource.isReadable()) {
                return ResponseEntity.ok()
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + resource.getFilename() + "\"")
                        .body(resource);

            } else {
                // 文件不存在或不可读时记录日志并返回404错误
                log.warn("请求的文件不存在或不可读 (实际查找的文件: {}): {}", filePath, filename);
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(ResultUtils.error(ErrorCode.SYSTEM_ERROR, "文件未找到: " + filename + "/" + actualFilename));
            }
        } catch (MalformedURLException e) {
            // 处理文件URL构造异常，返回400错误
            log.error("创建文件URL时出错: {}", filename, e);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ResultUtils.error(ErrorCode.PARAMS_ERROR, "请求的文件夹名无效: " + filename));
        } catch (Exception e) {
            // 处理其他未预期的异常，返回500错误
            log.error("获取文件时发生未知错误 (查找: {}): {}", filename, e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ResultUtils.error(ErrorCode.SYSTEM_ERROR, "服务器内部错误"));
        }
    }


    /**
     * 接收前端传递的PCD切割范围和位姿
     *
     * @param request 包含位姿和切割范围的请求体
     * @return 确认接收的 BaseResponse
     */
    @PostMapping("/process-by-pose")
    public BaseResponse<?> processPcdByPose(@RequestBody ProcessPcdRequest request) {
        // 打印到控制台
        log.info("接收到PCD处理请求: {}", request.toString());
    
        // 1. 首先处理mapName的发布
        if (request.getMapName() != null && !request.getMapName().trim().isEmpty()) {
            String mapName = request.getMapName().trim();
            
            // 调用ROS2服务发布地图名称
            try {
                ros2CommunicationService.publishmapFolder(mapName);
                log.info("ROS2 地图名称发布成功: {}", mapName);
                
                // 发布后加入100ms延时
                try {
                    Thread.sleep(100);
                    log.debug("地图名称发布后延时100ms完成");
                } catch (InterruptedException e) {
                    log.warn("延时被中断", e);
                    Thread.currentThread().interrupt();
                }
                
            } catch (Exception e) {
                log.error("ROS2 地图名称发布失败: {}", mapName, e);
                return ResultUtils.error(ErrorCode.SYSTEM_ERROR, "地图名称发布失败");
            }

            // 发布地图路径
            if (request.getFileName() != null && !request.getFileName().trim().isEmpty()) {
                String fileName = request.getFileName().trim();
                String mapPath = MapPaths.globalMap(pcdFilePath, fileName).toString();
                try {
                    ros2CommunicationService.publishMapPath(mapPath);
                    log.info("ROS2 地图路径发布成功: {}", mapPath);
                } catch (Exception e) {
                    log.error("ROS2 地图路径发布失败: {}", mapPath, e);
                }
            }
        } else {
            log.warn("请求中未包含有效的地图名称信息");
            return ResultUtils.error(ErrorCode.PARAMS_ERROR, "地图名称不能为空");
        }
    
        // 2. 原有的切割平面处理逻辑
        if (request.getCuttingPlane() != null) {
            ProcessPcdRequest.CuttingPlane cuttingPlane = request.getCuttingPlane();
            double zMin = cuttingPlane.getZMin() != null ? cuttingPlane.getZMin() : -0.4;
            double zMax = cuttingPlane.getZMax() != null ? cuttingPlane.getZMax() : 1.9;
            double[] zThreshold = {zMin, zMax};
    
            boolean publishSuccess = ros2CommunicationService.publishzThreshold(zThreshold);
            log.info("ROS2 z阈值发布{}成功: zMin={}, zMax={}", 
                    publishSuccess ? "" : "未", zMin, zMax);
        } else {
            log.warn("请求中未包含切割平面信息");
        }
    
        // 3. 原有的位姿处理逻辑
        if (request.getPose() != null) {
            ProcessPcdRequest.Pose pose = request.getPose();
            double x = 0.0, y = 0.0, z = 0.0;
            if (pose.getPosition() != null) {
                x = pose.getPosition().getX() != null ? pose.getPosition().getX() : 0.0;
                y = pose.getPosition().getY() != null ? pose.getPosition().getY() : 0.0;
                z = pose.getPosition().getZ() != null ? pose.getPosition().getZ() : 0.0;
            }
    
            double orientationX = 0.0, orientationY = 0.0, orientationZ = 0.0, orientationW = 1.0;
            if (pose.getOrientation() != null && pose.getOrientation().getQuaternion() != null) {
                ProcessPcdRequest.Quaternion quaternion = pose.getOrientation().getQuaternion();
                orientationX = quaternion.getX() != null ? quaternion.getX() : 0.0;
                orientationY = quaternion.getY() != null ? quaternion.getY() : 0.0;
                orientationZ = quaternion.getZ() != null ? quaternion.getZ() : 0.0;
                orientationW = quaternion.getW() != null ? quaternion.getW() : 1.0;
            }
    
            boolean publishSuccess = ros2CommunicationService.publishPgmHomeTf(
                    x, y, z, orientationX, orientationY, orientationZ, orientationW);
            log.info("ROS2 TF发布{}成功", publishSuccess ? "" : "未");
        } else {
            log.warn("请求中未包含位姿信息");
        }
    
        return ResultUtils.success("切割请求已收到");
    }
}
