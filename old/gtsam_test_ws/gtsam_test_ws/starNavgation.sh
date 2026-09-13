#!/bin/bash

# 增强日志功能
log() {
    local timestamp=$(date "+%Y-%m-%d %H:%M:%S")
    echo -e "[$timestamp] $1" | tee -a ros_startup.log
}

# 后台执行函数
execute_background() {
    local step_name=$1
    local command=$2
    log "Starting: $step_name"
    
    # 使用nohup后台运行并记录输出
    nohup bash -c "$command" >> ros_startup.log 2>&1 &
    local pid=$!
    
    sleep 1 # 等待进程启动
    if ps -p $pid > /dev/null; then
        log "$step_name started (PID: $pid)"
        return 0
    else
        log "$step_name failed to start"
        return 1
    fi
}

# 检查locale设置
if ! locale -a | grep -q "en_US.utf8"; then
    log "Warning: Missing en_US.UTF-8 locale. Run: sudo locale-gen en_US.UTF-8"
fi

log "==== ROS System Startup ===="

# 终端1: 启动LiDAR驱动
execute_background "LiDAR Driver" "source ~/lio_sam_test/install/setup.bash; ros2 launch livox_ros_driver2 rviz_MID360_launch.py" || exit 1

sleep 3
# 终端2: 启动LiDAR定位
execute_background "LiDAR Localization" "source ~/gtsam_test_ws/install/setup.bash; ros2 launch lidar_localization_ros2 lidar_localization.launch.py" || exit 1

sleep 3
# 终端3: 启动TF驱动
execute_background "TF Driver" "source ~/gtsam_test_ws/install/setup.bash; ros2 launch tf_to_pose tf_to_pose.launch.py " || exit 1

sleep 3
# 终端5: 启动车辆导航
execute_background "vehicle navigation" "source ~/gtsam_test_ws/install/setup.bash;ros2 launch vehicle_navigation vehicle_navigation.launch.py" || exit 1

sleep 3
# 终端5: 启动底盘驱动
execute_background "ros2plc" "source ~/robot_driver_ws/install/setup.bash;ros2 run ros2plc ros2plc" || exit 1


log "==== ROS System Initialized ===="
exit 0