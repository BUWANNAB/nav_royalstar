package com.ant.robot.model.request;

import lombok.Data;

@Data
public class RouteDetailRequest {

    private static final long serialVersionUID = 7651939114899936083L;

    private Long id;

    /**
     * 路线id
     */
    private Long routeId;

    /**
     * 站点id
     */
    private Long stationId;

    /**
     * 速度
     */
    private String speed;

    /**
     * 动作
     */
    private String action;

    /**
     * 安全防护区域
     */
    private String area;

    /**
     * 方向
     */
    private String direction;

    /**
     * 停留时间
     */
    private String stopTime;

    /**
     * 运行模式
     */
    private String runmode;

    /**
     * 导航模式：0-默认；1-RTK；2-SLAM
     */
    private String position;

    /**
     * 是否停车：0-不停车；1-停车；
     */
    private String stop;

    /**
     * 变道绕障属性 0：禁止，1：左变道，2：右变道，3左右皆可；
     */
    private String lanechange;

}
