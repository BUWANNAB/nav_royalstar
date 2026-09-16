package com.ant.robot.model.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.ToString;

import java.io.Serializable;
import java.util.Date;

@TableName(value ="t_param")
@Data
@ToString
public class Param implements Serializable{
    /**
     * 主键
     */
    @TableId(type = IdType.AUTO)
    private Long id;
    /**
     *车辆型号
     */
    private String vehicle_mode;
    /**
     * 导航类型
     */
    private String navigation_mode;
    /**
     * 经度原点
     */
    private String local_origin_longitude;
    /**
     * 纬度原点
     */
    private String local_origin_latitude;
    /**
     * 采样距离
     */
    private String samplInte;
    /**
     * 运行速度
     */
    private String speed_run;
    /**
     * 最大运行速度
     */
    private String speed_max;
    /**
     * 最低运行速度
     */
    private String speed_min;
    /**
     * 减速
     */
    private String speed_down;
    /**
     * 自转角度
     */
    private String rotation;
    /**
     * 停车距离
     */
    private String stop_set;
    /**
     * 遥控/自动切换
     */
    private String remote_mode;
    /**
     * 天线安装位置偏移量
     */
    private String relative_x;
    /**
     * 天线安装位置偏移量
     */
    private String relative_y;
    /**
     * 预瞄距离
     */
    private String forwordDis;
    /**
     * 轮轴距
     */
    private String wheelBase;
    /**
     * 左推进器系数
     */
    private String lpropellerl;
    /**
     * 右推进器系数
     */
    private String rpropellerl;
    /**
     * 叉车升起轴距
     */
    private String upward;
    /**
     * 叉车下落轴距
     */
    private String reduced;
    /**
     * 雷达扫描距离
     */
    private String scandis_max;
    /**
     * 安全防护参数
     */
    private String scandis_min;
    /**
     * 雷达外参-x
     */
    private String lidarinstallpara_x;
    /**
     * 雷达外参-y
     */
    private String lidarinstallpara_y;
    /**
     * 雷达外参-z
     */
    private String lidarinstallpara_z;
    /**
     * 雷达外参-yaw
     */
    private String lidarinstallpara_yaw;
    /**
     * 雷达外参-pitch
     */
    private String lidarinstallpara_pitch;
    /**
     * 雷达外参-roll
     */
    private String lidarinstallpara_roll;
    /**
     * 创建时间
     */
    private Date createTime;
    /**
     * 更新时间
     */
    private String robot_radius;
    /**
     * 机器人半径-
     */
    private String tool_radius;
    /**
     * 工具半径
     */
    private String coverage_direction;
    /**
     * 雷达外参-roll
     */
    private String point_spacing;
    /**
     * 边界点间距
     */
    private String enable_insertion;
    /**
     * 是否边界清扫
     */
    private String clean_mode;
    /**
     *当前地图
     */
    private String current_map;
    /**
     * 导航地图PCD名称
     */
    @TableField("nav_map_pcd_name")
    private String navMapPcdName;
    /**
     *开机点
     */
    private String boot_point;
    /**
     * 是否删除
     */
    private Integer isDelete;


    @TableField(exist = false)
    private static final long serialVersionUID = 1L;
}
