package com.ant.robot.model.request;

import lombok.Data;

import java.io.Serializable;
import java.util.List;


@Data
public class RouteAutoRequest implements Serializable{
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
     * 边界点
     */
    private String[] routeAutoData;
    /**
     * 地图层级
     */
    private String mapCoverage;
    /**
     * 规划方式，0-手动，1为自动
     */
    private String routesource;
    /**
     * 站点数据
     */
    private String[] rowData;

}
