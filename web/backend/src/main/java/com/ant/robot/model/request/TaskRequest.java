package com.ant.robot.model.request;

import lombok.Data;

import java.io.Serializable;

@Data
public class TaskRequest implements Serializable{
    private static final long serialVersionUID = 7759612453254777321L;

    private Long id;

    /**
     *任务描述
     */
    private String description;

    /**
     * 任务类型
     */
    private String executionType;

    /**
     * 任务优先级
     */
    private String priority;

    /**
     * 重复运行时间
     */
    private String repeatExecutionTime;

    /**
     * 运行结果
     */
    private String retryOnFailure;

    /**
     * 路线ID
     */
    private String routeId;

    /**
     * 路线名称
     */
    private String routeName;

    /**
     * 是否发送结果
     */
    private String sendNotification;

    /**
     * 单次运行时间
     */
    private String singleExecution;

    /**
     * 任务状态
     */
    private String status;

    /**
     * 上次运行时间
     */
    private String lastExecution;

    /**
     * 下次运行时间
     */
    private String nextExecution;

    /**
     * 运行周期
     */
    private String weekdays;
}
