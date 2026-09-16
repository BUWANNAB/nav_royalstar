package com.ant.robot.model.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 操作日志记录
 * @TableName t_run_log
 */
@TableName(value ="t_run_log")
@Data
public class RunLog implements Serializable {
    /**
     * 日志主键
     */
    @TableId(type = IdType.AUTO)
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