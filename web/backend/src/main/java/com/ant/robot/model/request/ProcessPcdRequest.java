package com.ant.robot.model.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class ProcessPcdRequest {
    // 顶层字段
    private String fileName;
    private String mapName;
    private CuttingPlane cuttingPlane;
    private Pose pose;

    // 1. 切割平面
    @Data
    public static class CuttingPlane {
        @JsonProperty("zMin")
        private Double zMin;
        @JsonProperty("zMax")
        private Double zMax;
    }

    // 2. 位姿
    @Data
    public static class Pose {
        private Position position;
        private Orientation orientation;
    }

    // 3. 位置 (Pose 的内部结构)
    @Data
    public static class Position {
        private Double x;
        private Double y;
        private Double z;
    }

    // 4. 姿态 (Pose 的内部结构)
    @Data
    public static class Orientation {
        // 对应文档表格
        private Quaternion quaternion;
        // 对应文档示例
        private Target target;
    }

    // 5. 四元数 (Orientation 的内部结构)
    @Data
    public static class Quaternion {
        private Double x;
        private Double y;
        private Double z;
        private Double w;
    }

    // 6. 目标点 (Orientation 的内部结构)
    @Data
    public static class Target {
        private Double x;
        private Double y;
        private Double z;
    }

    @Override
    public String toString() {
        return "ProcessPcdRequest{" +
                "fileName='" + fileName + '\'' +
                ", mapName='" + mapName + '\'' +
                ", cuttingPlane=" + cuttingPlane +
                ", pose=" + pose +
                '}';
    }
}
