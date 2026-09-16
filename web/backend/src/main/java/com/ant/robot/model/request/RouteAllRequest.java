package com.ant.robot.model.request;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

@Data
public class RouteAllRequest implements Serializable {
    private static final long serialVersionUID = 7651939004899936083L;

    private Long id;
    private String routeName;       // 路线名称
    private String mapCoverage;     // 地图层级
    private String routesource;      // 规划方式，0-手动，1为自动
    private String mapName;         // 地图名称
    private String routeGroup;      // 路线组号
    private String speed;           // 路线速度

    private List<RouteStation> stations; // 站点列表

    @Data
    public static class RouteStation implements Serializable {
        private Long id;            // 站点ID，-1表示需要新建
        private String stationName; // 站点名称
        private String stationCode; // 站点编码

        // 位置信息
        private String longitude;   // 经度
        private String latitude;     // 纬度
        private String positionX;    // X坐标
        private String positionY;    // Y坐标
        private String positionZ;    // Z坐标

        // 方向信息
        private String orientationX; // 四元数X
        private String orientationY; // 四元数Y
        private String orientationZ; // 四元数Z
        private String orientationW; // 四元数W

        // 路线详情信息
        private String speed;       // 速度
        private String action;      // 动作
        private String area;        // 安全防护区域
        private String direction;    // 方向
        private String stopTime;    // 停留时间
        private String position;    // 导航模式：0-默认；1-RTK；2-SLAM
        private String stop;        // 是否停车：0-不停车；1-停车
        private String runmode;     // 运行模式
        private String lanechange;//变道绕障属性 0：禁止，1：左变道，2：右变道，3左右皆可
    }
}