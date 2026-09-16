package com.ant.robot.model.request;

import lombok.Data;

@Data
public class RecordingRequest {
    /**
     * 录制间隔距离（米）
     */
    private Double distance;

    /**
     * 路线名称
     */
    private String routeName;

    /**
     * 地图名称
     */
    private String mapName;
}