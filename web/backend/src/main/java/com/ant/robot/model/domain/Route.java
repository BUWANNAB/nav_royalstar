package com.ant.robot.model.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 路线
 * @TableName t_route
 */
@TableName(value ="t_route")
@Data
public class Route implements Serializable {
    /**
     * 主键
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 路线名称
     */
    private String routeName;

    /**
     * 地图名称
     */
    private String mapName;

    /**
     * 路线组号
     */
    private String routeGroup;

    /**
     * 路线速度
     */
    private String speed;

    /**
     * 地图层级
     */
    private String mapCoverage;

    /**
     * 规划方式，0-手动，1为自动
     */
    private String routesource;

    /**
     * 站点id集合
     */
    private String stationIds;

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