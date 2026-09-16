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
 * 站点
 * @TableName t_station
 */
@TableName(value ="t_station")
@Data
@ToString
public class Station implements Serializable {
    /**
     * 主键
     */
    @TableId(type = IdType.AUTO)
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

    /**
     * 地图名称
     */
    @TableField("map_name")
    private String mapName;

    @TableField(exist = false)
    private static final long serialVersionUID = 1L;
}