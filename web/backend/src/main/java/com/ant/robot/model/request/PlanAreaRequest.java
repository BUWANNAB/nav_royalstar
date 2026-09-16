package com.ant.robot.model.request;

import lombok.Data;
import java.util.List;

@Data
public class PlanAreaRequest {
    /**
     * 规划模式
     */
    private Integer planMode;

    /**
     * 绘制区域坐标列表，每个点包含[x, y]坐标
     */
    private List<List<Double>> drawArea;
}