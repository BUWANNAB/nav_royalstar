package com.ant.robot.model.request;

import lombok.Data;

import java.io.Serializable;


@Data
public class RouteMapRequest implements Serializable{
    private static final long serialVersionUID = 7651478204894454483L;

    private Long id;
    /**
     * 路线名称
     */
    private String routeName;
    /**
     * 站点数据
     */
    private String[] routeMapData;
    /**
     * 站点数据
     */
    private String[] avoidanceArr;
    /**
     * 地图层级
     */
    private String mapCoverage;
    /**
     * 规划方式，0-手动，1为自动
     */
    private String routesource;



}
