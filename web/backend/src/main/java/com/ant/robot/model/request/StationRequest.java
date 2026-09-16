package com.ant.robot.model.request;

import lombok.Data;

import java.io.Serializable;

/**
 * @author ChenWeihan
 */
@Data
public class StationRequest implements Serializable {

    private static final long serialVersionUID = 3353706753614522186L;

    private Long id;

    /**
     * 站点名称
     */
    private String stationName;
    /**
     * 站点编码
     */
    private String stationCode;

    /**
     * 站点经度
     */
    private String longitude;

    /**
     * 站点纬度
     */
    private String latitude;

    /**
     * 站点x坐标
     */
    private String positionX;

    /**
     * 站点y坐标
     */
    private String positionY;

    /**
     * 站点z坐标
     */
    private String positionZ;

    /**
     * 四元数X
     */
    private String orientationX;

    /**
     * 四元数Y
     */
    private String orientationY;

    /**
     * 四元数Z
     */
    private String orientationZ;

    /**
     * 四元数W
     */
    private String orientationW;

    /**
     * 地图名称
     */
    private String mapName;
}
