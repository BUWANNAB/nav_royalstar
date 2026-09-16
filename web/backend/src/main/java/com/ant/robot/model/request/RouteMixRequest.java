package com.ant.robot.model.request;

import lombok.Data;

import java.io.Serializable;
import java.util.List;


@Data
public class RouteMixRequest implements Serializable{
    private static final long serialVersionUID = 7651478204894454483L;

    private Long id;
    /**
     * 路线名称
     */
    private String routeName;

    /**
     * 站点数据
     */
    private List<StationRequest> stations;
    /**
     * 地图层级
     */
    private String mapCoverage;
    /**
     * 站点数据
     */
    private String[] rowData;
    /**
     * 规划方式，0-手动，1为自动
     */
    private String routesource;
    /**
     * 路线速度
     */
    private String speed;

}
