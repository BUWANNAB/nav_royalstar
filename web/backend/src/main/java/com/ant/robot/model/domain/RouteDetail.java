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
 * 路线详情。
 *
 * <p>发送机器人 {@code /path_point} 时，每个路径点固定映射到相对字段 {@code [0]..[8]}：
 * x、y、目标 yaw（弧度）、点位 ID、速度、运行模式、定位模式、预留值 0、作业时长（秒）。
 * {@code lanechange} 和 {@code stop} 仍属于业务字段，但不占用硬件协议的相对字段 {@code [7]}、{@code [8]}。</p>
 * @TableName t_route_detail
 */
@TableName(value ="t_route_detail")
@Data
@ToString
public class RouteDetail implements Serializable {
    /**
     * 主键
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 路线id
     */
    private Long routeId;

    /**
     * 站点id
     */
    private Long stationId;

    /** 点速度，单位 m/s；允许负数表示倒车。 */
    private String speed;

    /** 安全防护区域，业务/PLC字段，不进入固定 9 字段的 path_point 数组。 */
    private String area;

    /** 站点动作编码，业务/PLC字段，不进入固定 9 字段的 path_point 数组。 */
    private String action;

    /** 目标航向角，数据库保存单位为度，发布 path_point 时转换为弧度。 */
    private String direction;

    /** 定位模式，对应 path_point 的相对字段 [6]；具体枚举以机器人硬件协议为准。 */
    private String position;

    /** 是否停车：0-不停车，1-停车；不替代 path_point 的相对字段 [8] 作业时长字段。 */
    private String stop;

    /** 运行模式：0-追踪，1-自转，对应 path_point 的相对字段 [5]。 */
    private String runmode;

    /** 变道绕障属性：0-禁止，1-左变道，2-右变道，3-左右皆可；不进入 path_point 的相对字段 [7] 预留字段。 */
    private String lanechange;

    /** 作业时长，单位秒，对应 path_point 的相对字段 [8]。 */
    private String stopTime;

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
