package com.ant.robot.model.request;

import lombok.Data;

import java.io.Serializable;

/**
 * @author ChenWeihan
 */
@Data
public class OrderRequest implements Serializable {

    private static final long serialVersionUID = 7826345232869630080L;
    /**
     * 路线id
     */
    private Long routeId;

    /**
     * 站点id集合
     */
    private String stationIds;
}
