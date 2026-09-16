package com.ant.robot.model.request;

import lombok.Data;

import java.io.Serializable;

@Data
public class RunLogRequest implements Serializable{
    private static final long serialVersionUID = 7758612453254777321L;

    private Long id;

    /**
     * 路线信息
     */
    private String routeMsg;

    /**
     * 机器运行
     */
    private String carRun;

    /**
     * 机器停止
     */
    private String carStop;

    /**
     * 作业取消
     */
    private String workCancel;
}
