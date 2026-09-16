package com.ant.robot.model.request;

import lombok.Data;
import lombok.Getter;

import java.io.Serializable;
import java.util.List;

/**
 * @author ChenWeihan
 */
@Data
public class RouteRequest implements Serializable {

    private static final long serialVersionUID = 7651939004899936083L;

    private Long id;
    /**
     * 路线名称
     */
    private String routeName;
    /**
     * 地图层级
     */
    private String mapCoverage;
    /**
     * 规划方式，0-手动，1为自动
     */
    private String routesource;
    /**
     * 地图名称
     */
    private String mapName;
    /**
     * 路线组号
     */
    private String routeGroup;
    /**
     * 路线速度
     */
    private String speed;
    /**
     * 站点id集合
     */
    private String stationIds;
    /**
     * 站点数据
     */
    private String[] rowData;
    /**
     * 站点数据
     */
    private String[] avoidanceArr;
    /**
     * 天地图
     */
    private String[] routeMapData;
    /**
     * 自动规划
     */
    private String[] routeAutoData;
    /**
     * 融合地图和自动规划
     */
    private List<StationRequest> stations;

    /**
     * 路线详情列表
     */
    private List<RouteDetailRequest> routeDetails;

    private List<Points> locations;
    /**
     * 机器宽度
     */
    private double stepSize;
    /**
     * 起点索引
     */
    private int startIndex;

    private int nextIndex;

    private double offset;

    @Getter
    @Data
    public static class Points {
        private double latitude;
        private double longitude;

        public Points() {}
        public Points(double x, double y) {
            this.latitude = x;
            this.longitude = y;
        }
    }
}
