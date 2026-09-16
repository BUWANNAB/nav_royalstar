package com.ant.robot.model.request;

import lombok.Data;

import java.io.Serializable;

@Data
public class SensorRequest implements Serializable{

    private static final long serialVersionUID = 7458632159654785321L;

    private Long id;

    /**
     * 站点名称
     */
    private String stationName;

    /**
     * 温度
     */
    private String temp;

    /**
     * 氧气浓度
     */
    private String O2;

    /**
     * 酸碱度
     */
    private String PH;
}
