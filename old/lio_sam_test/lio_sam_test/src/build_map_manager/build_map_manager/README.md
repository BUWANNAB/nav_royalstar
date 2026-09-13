# build_map_manager

这是一个 ROS2 Humble 建图管理节点，用于接收前端 `/buildmap` 指令，自动启动 Livox msg 驱动和 LIO-SAM 建图，并在收到地图名后调用 `/lio_sam/save_map` 保存 PCD 地图。

## 话题协议

前端发布：

```bash
/buildmap
std_msgs/msg/String
```

消息内容：

```text
start      开始建图
地图名      结束建图并保存，例如 qin4、shuobao-pgm、map_001
```

节点反馈：

```bash
/buildmap_status
std_msgs/msg/String
```

常见状态：

```text
idle
starting
mapping
map_data_ready
saving
saved:/home/lyrobot005/inHome/pcd/地图名
already_mapping
not_mapping
no_map_data
invalid_map_name
save_failed
```

## 安装

把整个 `build_map_manager` 文件夹复制到工作空间 src 下：

```bash
cp -r build_map_manager ~/lio_sam_test/src/
cd ~/lio_sam_test
colcon build --packages-select build_map_manager
source install/setup.bash
```

## 启动

```bash
source ~/lio_sam_test/install/setup.bash
ros2 launch build_map_manager build_map_manager.launch.py
```

## 测试

模拟前端开始建图：

```bash
ros2 topic pub --once /buildmap std_msgs/msg/String "{data: 'start'}"
```

查看状态：

```bash
ros2 topic echo /buildmap_status
```

确认 LIO-SAM 是否已有建图输出：

```bash
ros2 topic hz /lio_sam/mapping/cloud_registered
ros2 topic hz /lio_sam/mapping/odometry
```

模拟前端结束建图并保存：

```bash
ros2 topic pub --once /buildmap std_msgs/msg/String "{data: 'qin4'}"
```

保存路径：

```text
/home/lyrobot005/inHome/pcd/qin4
```

## 注意

1. 节点启动雷达驱动使用的是：

```bash
ros2 launch livox_ros_driver2 msg_MID360_launch.py
```

不要改成 `rviz_MID360_launch.py`，否则 `/livox/lidar` 会变成 PointCloud2，当前 LIO-SAM 的 `lio_sam_imageProjection` 订阅 CustomMsg 会收不到点云。

2. 节点收到过 `/lio_sam/mapping/cloud_registered` 非空点云后，才允许保存地图，防止空地图触发：

```text
[pcl::PCDWriter::writeBinary] Input point cloud has no data!
```

3. 地图名只允许英文字母、数字、下划线和中划线，例如：

```text
qin4
shuobao-pgm
map_001
```

不允许 `/`、`..`、空格，防止错误拼接路径。
