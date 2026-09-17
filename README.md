# Royalstar BlueAnt AGV

ROS 2 Humble 导航、Livox MID-360 建图与 Java Web 操作系统的源码交付仓库。面向 Ubuntu 22.04 / x86_64 工控机，包含 ROS 工作区、网页、后端、部署脚本和构建依赖源码。

> 当前支持手动切换建图与导航，支持通过 `/map_path` 更新定位地图；尚未实现完整模式热切换。节点启动成功不代表硬件、定位精度和车辆运行已通过验收。

## 功能与组成

| 功能 | 组件 | 说明 |
| --- | --- | --- |
| 雷达采集 | livox_ros_driver2、Livox-SDK2 | MID-360 点云和 IMU 数据 |
| 3D 建图 | lio_sam、GTSAM | 雷达惯性建图、轨迹与点云地图 |
| 建图管理 | build_map_manager | 接收开始、停止及保存命令，管理所属雷达和 LIO-SAM 进程 |
| 2D 地图 | pcd2pgm | 点云切割、栅格地图生成和保存 |
| 定位 | lidar_localization_ros2、ndt_omp_ros2 | 点云与地图匹配，发布定位变换 |
| 网页位姿 | tf_to_pose、Java ROS bridge | 将地图坐标系车辆位姿送到网页 |
| 路线管理 | Spring Boot 后端、MySQL | 站点、路线和全局设置 |
| 导航控制 | vehicle_navigation | 路线跟踪；依赖硬件授权配置 |
| 底盘通信 | ros2plc | Modbus TCP 读写、速度和 PLC 启动命令 |
| 浏览器 ROS 接口 | rosbridge_websocket、rosapi | WebSocket 话题通信与查询 |

## 仓库结构

```text
.
├── blueant_nav_ws/
│   ├── src/
│   │   ├── drivers/       # Livox 驱动
│   │   ├── mapping/       # 建图、管理、2D 转换、参数服务器
│   │   ├── localization/  # 定位、NDT
│   │   ├── navigation/    # 位姿转换、导航控制
│   │   └── hardware/      # PLC、硬件绑定库
│   ├── third_party/       # GTSAM、Livox-SDK2，独立构建
│   ├── scripts/           # 依赖检查、安装和单线程构建
│   └── docs/BUILD.md
├── web/
│   ├── backend/           # Java 17 / Spring Boot / Maven
│   ├── frontend/          # 网页资源，由 Java 服务提供
│   ├── deploy/            # start、stop、check、install、systemd
│   ├── pcd/               # 3D 地图
│   ├── maps/              # 2D 地图及配置
│   └── runtime/           # 本机日志、PID 等，不入库
├── docs/
└── SOURCE_MANIFEST.txt
```

`ros2_java_ws` 是另外准备的 ROS Java/JNI 工作区，不属于本仓库导航工作区。构建导航时加载 ROS Humble，避免将 ROS Java 工作区混入构建环境。

## 新机器准备

详细安装步骤见 [NEW_MACHINE_SETUP.md](docs/NEW_MACHINE_SETUP.md)，ROS 构建依赖见 [BUILD.md](blueant_nav_ws/docs/BUILD.md)。需要准备 Java 17、Maven、MySQL、ROS Humble、colcon、rosdep、rosbridge 及 ROS Java/JNI 环境。数据库按安装文档初始化，密码留在本机配置中。

GTSAM 用于 SLAM 因子图优化；Livox-SDK2 用于雷达设备通信。首次安装外部依赖后再编译 ROS 包：

```bash
cd blueant_nav_ws
sudo BLUEANT_BUILD_JOBS=1 bash scripts/install-vendored-dependencies.sh
source /opt/ros/humble/setup.bash
rosdep install --from-paths src --ignore-src --skip-keys gtsam -r -y
bash scripts/build-workspace.sh
source install/setup.bash
```

构建脚本使用单线程，降低内存占用；没有安装开发依赖时不能仅靠 colcon 保证成功。

## 路径配置与换机

以实际仓库位置为部署根，避免写死用户名和目录。在仓库根执行：

```bash
export ROBOT_APP_DIR="$(pwd)/web"
export NAV_WS="$(pwd)/blueant_nav_ws"
export ROS_JAVA_WS="$HOME/ros2_java_ws"
export ROBOT_PCD_DIR="$ROBOT_APP_DIR/pcd"
export ROBOT_MAP_DIR="$ROBOT_APP_DIR/maps"
```

参考 `web/deploy/robot.env.example` 创建本机 `web/deploy/robot.env`。脚本会读取该文件；其中的值可能覆盖终端环境变量，应保持一致。配置数据库、工作区、地图和设备地址时使用实际部署值，不提交密码或机器授权。

| 项目 | 配置/检查 |
| --- | --- |
| 3D 地图 | ROBOT_PCD_DIR |
| 2D 地图 | ROBOT_MAP_DIR |
| 初始定位地图 | BLUEANT_MAP_PCD，指向实际 GlobalMap.pcd |
| ROS 导航工作区 | NAV_WS |
| Java/JNI 工作区 | ROS_JAVA_WS |
| 雷达网卡 | 驱动 JSON 的 host IP 必须匹配连接雷达的网卡 |
| PLC | ros2plc 的 server_ip、server_port 参数 |
| 导航授权 | authorized_hardware_id / BLUEANT_AUTHORIZED_HARDWARE_ID，按实际机器设置 |

默认脚本仍按 ROS Humble 部署。定位 launch 中保留了原有雷达安装变换（后移 0.5 米、旋转约 90°）；必须核对实物并标定，不能当作所有机器的通用值。

## 启动 Java 与网页

使用普通机器人用户，不要用 sudo 运行 Java，以免 HOME 变成 `/root`：

```bash
cd web
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export PATH="$JAVA_HOME/bin:$PATH"
bash deploy/start/run-java.sh
```

脚本加载 ROS 和 ROS Java 工作区，以 Maven `spring-boot:run` 运行源码；首次启动需要下载依赖，并不自动启动全部机器人节点。保持终端运行，另开终端检查：

```bash
curl -I http://127.0.0.1:8088/
ss -lntp | grep -E '8088|9090'
```

笔记本访问 `http://工控机实际IP:8088/`，不要沿用旧机器地址。9090 是 rosbridge，需按部署说明单独启动或由已有服务提供；8088 返回 200 只证明网页服务响应。

## 建图与保存

1. 停止任务并确认车辆静止，停止导航控制和定位，避免与建图并行运行。
2. 保留 Java、rosbridge、参数服务器及 pcd2pgm。
3. 启动 build_map_manager，传入实际工作区与 PCD 根目录；网页开始录制后，由管理节点启动消息模式雷达和 LIO-SAM。
4. 等待 map_data_ready，保持雷达固定，按现场安全流程采集地图。
5. 按网页流程停止录制并保存，确认保存成功；直接结束进程不会自动保存地图。
6. 生成、切割并保存 2D 地图，核对分辨率及地图原点。

```bash
source /opt/ros/humble/setup.bash
source "$NAV_WS/install/setup.bash"
ros2 launch build_map_manager build_map_manager.launch.py \
  workspace_setup:="$NAV_WS/install/setup.bash" \
  map_base_dir:="$ROBOT_PCD_DIR"
```

不要重复启动已在运行的管理节点、雷达或地图转换节点。无显示环境中的 RViz 报错与核心建图节点故障应分别排查。

## 建图完成后切换导航

1. 确认地图已保存，选择对应 2D 地图并设为导航地图。
2. 在建图 launch 所在终端按 Ctrl+C，停止管理节点及其所属建图进程；检查 LIO-SAM 是否残留。
3. 配置对应的 3D 地图路径，启动导航脚本。
4. 确认定位加载成功，必要时通过 `/map_path` 显式注入地图。
5. 设置初始位置与朝向，确认网页红点和现场一致后，才进入车辆测试。

```bash
export BLUEANT_MAP_PCD="$ROBOT_PCD_DIR/202609171123/GlobalMap.pcd"
test -r "$BLUEANT_MAP_PCD"
bash "$ROBOT_APP_DIR/deploy/start/start-navigation.sh"
ros2 topic pub --once --spin-time 2 /map_path std_msgs/msg/String \
  "{data: '$BLUEANT_MAP_PCD'}"
ros2 lifecycle get /lidar_localization
```

导航脚本启动雷达、建图管理、定位、tf_to_pose、pcd2pgm、vehicle_navigation 和 ros2plc。参数服务器与 rosbridge 需保持可用。PLC 驱动启动与底盘使能是两件事；PLC 启动链路为 Java `/ros2/plcstart` → `/plc_start` → ros2plc 写寄存器。发布成功不能替代设备实际使能反馈。

**此处示例路径只适用于随仓库提供的现场地图；新场地必须换成实际新地图。完整建图/导航模式热切换尚未实现。**

## Java 与 ROS 数据契约

| 接口 | 方向与用途 |
| --- | --- |
| /buildmap | Java → 管理节点：开始、停止及地图保存命令 |
| /buildmap_status | 管理节点 → Java：启动、就绪、保存及失败状态 |
| /lio_sam/save_map | 管理节点调用 LIO-SAM 保存服务 |
| /map_path | 定位节点接收 GlobalMap.pcd 路径，更新定位地图 |
| /tf_pose | tf_to_pose → Java、导航控制：地图坐标系位姿 |
| carCurrentPosition | Java WebSocket → 网页：XY、四元数、航向等 |
| /cmd_vel | 导航 → PLC 驱动：速度 |
| /plc_start | Java → PLC 驱动：PLC 启动信号 |

网页 2D 图片与定位 3D 点云必须来自同一张地图；更换地图后不能盲目沿用旧路线。

## 本次现场修复（2026-09-17）

- 建图状态接收 QoS 与 ROS 发布端匹配，解决 command_pending。
- 已有近期非空地图点云时，重复开始建图返回就绪；启动期间继续等待，减少 already_mapping 误报。
- 经纬度原点缺失时仍广播室内位姿，解决网页红点消失。
- 室内路线保存与更新允许经纬度原点缺失，XY 零坐标不再判为空。

完整记录见 [现场版本同步说明](docs/FIELD_ALIGNMENT_20260917.md)。两张配套示例地图为 `20269171109`、`202609171123`，3D 在 web/pcd，2D 在 web/maps；map.yaml 图片引用为相对路径。

## 验证结果与待验收项

| 项目 | 状态 |
| --- | --- |
| 021 建图管理包编译、Java 编译启动 | 已现场验证 |
| 重复录制请求、WebSocket 室内位姿 | 已现场验证 |
| 示例地图加载、定位 active | 已现场验证 |
| PLC TCP 与 Modbus 读写 | 已现场验证 |
| Windows 同步源码与两组地图 | 17 个文件与下载快照校验一致 |
| 长期漂移、雷达安装标定、定位绝对精度 | 待验收 |
| 路线保存修复后的最终用户保存结果 | 待验收 |
| 完整车辆运行、全部故障恢复 | 待验收 |
| 建图与导航完整模式热切换 | 未实现 |

匹配 has_converged=1、服务 active 或进程存在，都不能单独证明定位准确。两秒点云检查也不代表所有建图子进程健康。

## 常见排查

| 现象 | 优先检查 |
| --- | --- |
| 8088 连接被拒绝 | Java 初始化日志、Java 17、端口、Maven 依赖 |
| JNI 加载失败 | ROS Java setup、LD_LIBRARY_PATH、CPU 架构 |
| command_pending | 管理节点是否收到命令、状态 QoS、ROS 通信环境 |
| already_mapping | 是否已有录制、点云是否新鲜、子进程是否异常 |
| 红点不显示 | /tf_pose、Java WebSocket、地图选择与显示坐标 |
| 静止定位漂移 | 雷达固定、地图质量、安装变换、匹配参数、初始位姿 |
| 底盘不动 | PLC 连通性、实际使能反馈、急停、运行模式、速度链路 |
| 停止后节点复活 | 先停止 launch 或 systemd，检查 respawn 和重复进程 |

## 更多文档

- [新机安装](docs/NEW_MACHINE_SETUP.md)
- [ROS 构建](blueant_nav_ws/docs/BUILD.md)
- [单工作区迁移](docs/SINGLE_WS_PLAN.md)
- [地图路径对齐](docs/PATH_ALIGNMENT_PLAN.md)
- [前后端架构](docs/FRONTEND_BACKEND_ARCHITECTURE.md)

构建产物、运行日志、本机 robot.env、数据库密码和机器授权不入库。示例地图是测试数据，不是导航精度保证。
