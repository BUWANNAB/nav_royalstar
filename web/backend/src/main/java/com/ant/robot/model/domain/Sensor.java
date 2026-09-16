package com.ant.robot.model.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.ToString;

import java.io.Serializable;
import java.util.Date;

/**
 * 水质传感器采样数据。
 * @TableName t_sensor
 */
@TableName(value ="t_sensor")
@Data
@ToString
public class Sensor implements Serializable{
    /**
     * 主键
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 温度
     */
    private String temp;

    /** 溶解氧（数据库字段：o2）。 */
    private String O2;

    /** 酸碱度（数据库字段：ph）。 */
    private String PH;

    /** 检测站号/站点名称（数据库字段：stationname）。 */
    private String stationName;

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
