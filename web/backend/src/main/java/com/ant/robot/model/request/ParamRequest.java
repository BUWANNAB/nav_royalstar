package com.ant.robot.model.request;

import lombok.Data;

import java.io.Serializable;
import java.util.Date;

@Data
public class ParamRequest implements Serializable {
    private static final long serialVersionUID = 7758612453254777321L;

    private Long id;
    /**
     * 车辆型号
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
     * 更新时间
     */
    private Date updateTime;
    /**
     * 机器人半径
     */
    private String robot_radius;
    /**
     * 工具半径
     */
    private String tool_radius;
    /**
     * 覆盖方向
     */
    private String coverage_direction;
    /**
     * 边界点间距
     */
    private String point_spacing;
    /**
     * 是否边界清扫
     */
    private String enable_insertion;
    /**
     * 清扫模式
     */
    private String clean_mode;
    /**
     * 当前地图
     */
    private String current_map;

    // 以下是新增的参数
    /**
     * 安全距离
     */
    private String safety_distance;
    /**
     * 是否从顶部开始
     */
    private String start_from_top;
    /**
     * 是否显示图像
     */
    private String show_image;
    /**
     * 方向推进器系数
     */
    private String dir_propeller;
    /**
     * 初始推进器系数
     */
    private String init_propeller;
    /**
     * 角度阈值
     */
    private String angle_threshold;
    /**
     * 关闭履带
     */
    private String close_track;
    /**
     * 自转角系数
     */
    private String vv_wk;
    /**
     * 运行模式
     */
    private String run_mode;
    private String boot_point;


}