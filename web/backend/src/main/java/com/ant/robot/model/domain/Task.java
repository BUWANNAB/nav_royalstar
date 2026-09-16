package com.ant.robot.model.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.ToString;

import java.io.Serializable;
import java.util.Date;

@TableName(value ="t_task")
@Data
@ToString
public class Task implements Serializable{
    /**
     * 主键
     */
    @TableId(type = IdType.AUTO)
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

    /**
     * 创建时间
     */
    private Date createTime;

    /**
     * 更新时间
     */
    private Date updateTime;

    /**
     * 是否删除
     */
    private Integer isDelete;

    @TableField(exist = false)
    private static final long serialVersionUID = 1L;
}
