package com.ant.robot.model.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.io.Serializable;
import java.util.Date;
import lombok.Data;

/**
 * 操作日志记录
 * @TableName t_log
 */
@TableName(value ="t_log")
@Data
public class Log implements Serializable {
    /**
     * 日志主键
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 模块标题
     */
    private String title;

    /**
     * 日志内容
     */
    private String content;

    /**
     * 方法名称
     */
    private String method;

    /**
     * 请求方式
     */
    private String requestMethod;

    /**
     * 操作人员
     */
    private String userAccount;

    /**
     * 请求url
     */
    private String requestUrl;

    /**
     * 请求ip地址
     */
    private String ip;

    /**
     * 请求参数
     */
    private String requestParam;

    /**
     * 方法响应参数
     */
    private String reponseResult;

    /**
     * 操作状态（0正常 1异常）
     */
    private Integer status;

    /**
     * 错误消息
     */
    private String errorMsg;

    /**
     * 操作时间
     */
    private Date operTime;

    /**
     * 方法执行耗时（单位：毫秒）
     */
    private Long holdTime;

    @TableField(exist = false)
    private static final long serialVersionUID = 1L;
}