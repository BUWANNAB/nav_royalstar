package com.ant.robot.model.request;

import lombok.Data;

import java.io.Serializable;

/**
 * @author ChenWeihan
 */
@Data
public class WaterdepthRequest implements Serializable {

    private static final long serialVersionUID = 3353706753614522186L;

    private Long id;

    /**
     * 经度
     */
    private String longitude;

    /**
     * 纬度
     */
    private String latitude;

    /**
     * 水深
     */
    private String depth;

}
