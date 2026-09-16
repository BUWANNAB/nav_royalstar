# 单一 ROS 2 工作区（blueant_nav_ws）设计与迁移方案

> 状态：本仓库侧改造已完成；机器人侧步骤待现场执行。
> 相关目录：`blueant_nav_ws/`、`web/deploy/`、`SOURCE_MANIFEST.txt`（原 `old/` 旧工作区快照已于 2026-09-16 移除）。

## 1. 背景

现场启动脚本 `start-navigation.sh` 原本从 **4 个独立工作区** 加载环境：

| 启动组件 | 原工作区 | 原包路径 |
|---|---|---|
| livox | `~/lio_sam_test` | `src/livox_ros_driver2` |
| build_map_manager | `~/lio_sam_test` | `src/build_map_manager` |
| （建图） | `~/lio_sam_test` | `src/LIO-SAM-MID360` |
| localization | `~/gtsam_test_ws` | `src/lidar_localization_ros2`（依赖 `src/ndt_omp_ros2`） |
| tf_to_pose | `~/gtsam_test_ws` | `src/tf_to_pose` |
| vehicle_navigation | `~/gtsam_test_ws` | `src/vehicle_navigation`（依赖 `src/hardware_bind_lib`） |
| pcd2pgm | `~/coverage_ws` | `src/pcd2pgm`（依赖 `src/parameter_server`） |
| ros2plc | `~/robot_driver_ws` | `src/ros2plc` |

4 个工作区各自 `colcon build`，启动时依次 `source` 4 份环境；包间依赖顺序（`hardware_bind_lib → vehicle_navigation`、
`ndt_omp_ros2 → lidar_localization_ros2`、`parameter_server → pcd2pgm`）只能靠人工保证，`package.xml` 漏声明时容易出现构建失败。

## 2. 目标

**一个工作区** `blueant_nav_ws` 承载全部导航功能包，按功能模块一级目录组织（每个模块一个文件夹，直接放该功能的包），
整体同步到机器人 `~/blueant_nav_ws` 后只 `source` 一次环境。

### 目标结构

```
blueant_nav_ws/
├── src/
│   ├── drivers/        # 模块：驱动    —— livox_ros_driver2
│   ├── mapping/        # 模块：建图    —— lio_sam、build_map_manager、pcd2pgm、parameter_server
│   ├── localization/   # 模块：定位    —— lidar_localization_ros2、ndt_omp_ros2
│   ├── navigation/     # 模块：导航    —— tf_to_pose、vehicle_navigation
│   └── hardware/       # 模块：硬件    —— ros2plc、hardware_bind_lib
├── third_party/        # gtsam、Livox-SDK2（非 ROS 构建期依赖，带 COLCON_IGNORE）
├── scripts/            # build-workspace.sh / check-dependencies.sh / install-vendored-dependencies.sh
├── docs/BUILD.md
└── README.md
```

### 旧 → 新 路径对照

| 原位置 | 新位置 |
|---|---|
| `lio_sam_test/src/livox_ros_driver2` | `blueant_nav_ws/src/drivers/livox_ros_driver2` |
| `lio_sam_test/src/LIO-SAM-MID360` | `blueant_nav_ws/src/mapping/lio_sam` |
| `lio_sam_test/src/build_map_manager` | `blueant_nav_ws/src/mapping/build_map_manager` |
| `gtsam_test_ws/src/lidar_localization_ros2` | `blueant_nav_ws/src/localization/lidar_localization_ros2` |
| `gtsam_test_ws/src/ndt_omp_ros2` | `blueant_nav_ws/src/localization/ndt_omp_ros2` |
| `gtsam_test_ws/src/tf_to_pose` | `blueant_nav_ws/src/navigation/tf_to_pose` |
| `gtsam_test_ws/src/vehicle_navigation` | `blueant_nav_ws/src/navigation/vehicle_navigation` |
| `gtsam_test_ws/src/hardware_bind_lib` | `blueant_nav_ws/src/hardware/hardware_bind_lib` |
| `robot_driver_ws/src/ros2plc` | `blueant_nav_ws/src/hardware/ros2plc` |
| `coverage_ws/src/pcd2pgm` | `blueant_nav_ws/src/mapping/pcd2pgm` |
| `coverage_ws/src/parameter_server` | `blueant_nav_ws/src/mapping/parameter_server` |

## 3. 本仓库已完成改动

1. 新增 `blueant_nav_ws/`：`src/`、`third_party/`、`scripts/`、`docs/BUILD.md` 复制自原仓库对应位置，工作区自包含。
2. `blueant_nav_ws/third_party/COLCON_IGNORE`：防止 colcon 把 `gtsam`（自带 `package.xml`）当作 ROS 包参与构建。
3. 原 `src/`、根 `scripts/`、`third_party/`、`docs/BUILD.md` 过渡副本已于 2026-09-15 清除（内容由 `blueant_nav_ws/` 取代；如需回溯可从 Git 历史恢复）。此前 `src/` 曾加 `COLCON_IGNORE` 防止根目录 colcon 重名扫描。
4. 部署脚本切换为单 ws：
   - `deploy/robot.env.example`：`LIO_SAM_WS / NAVIGATION_WS / ROBOT_DRIVER_WS / PCD2PGM_WS` → 单一 `NAV_WS="$HOME/blueant_nav_ws"`；
   - `deploy/start-navigation.sh`：只 `require` + `source` 一个 ws，7 个组件全部从该 ws 启动（`build_map_manager` 的 `workspace_setup` 参数同步改）；
   - `deploy/stop-navigation.sh`、`check-navigation.sh` 不涉及工作区路径，无需改动。
5. web 内其余引用同步为 `~/blueant_nav_ws`：`star_lio_sam.sh`、`ROS2Controller.java`（2 处 `ros2 lifecycle set`）、`RobotSystem.java`（1 处 `ros2 node list`）。
6. 根 `README.md` 重写为交付仓库布局说明；`SOURCE_MANIFEST.txt` 左列更新为 `blueant_nav_ws/src/...` 新路径。

> 注意：现场已有的 `deploy/robot.env` 仍是 4 个 `*_WS` 变量，需要在机器人上按新模板更新为 `NAV_WS` 后再切换启动方式。

## 4. 机器人上机步骤（建议顺序）

```bash
# 0) 同步工作区：把本仓库 blueant_nav_ws/ 整体复制到 $HOME/blueant_nav_ws
#    （旧 4 个工作区先不要删除，用于回滚）

# 1) 首次安装第三方依赖（Livox-SDK2 / GTSAM → /usr/local，需要 sudo）
cd ~/blueant_nav_ws
sudo BLUEANT_BUILD_JOBS=1 ./scripts/install-vendored-dependencies.sh

# 2) 编译新工作区（独立目录，不影响旧 4 个 ws）
source /opt/ros/humble/setup.bash
rosdep install --from-paths src --ignore-src --skip-keys gtsam -r -y
./scripts/build-workspace.sh
source install/setup.bash

# 3) 组件验证（对照 deploy/check-navigation.sh 的节点清单）
ros2 launch livox_ros_driver2 rviz_MID360_launch.py
ros2 launch lidar_localization_ros2 lidar_localization.launch.py
ros2 launch tf_to_pose tf_to_pose.launch.py
ros2 launch pcd2pgm pcd2pgm_launch.py map_base_dir:=<maps 目录>
ros2 launch vehicle_navigation vehicle_navigation.launch.py
ros2 run ros2plc ros2plc
ros2 launch build_map_manager build_map_manager.launch.py \
    workspace_setup:=$HOME/blueant_nav_ws/install/setup.bash map_base_dir:=<pcd 目录>
```

4. 切换现场脚本：在 `deploy/robot.env` 中把 4 个 `*_WS` 替换为 `NAV_WS="$HOME/blueant_nav_ws"`；停止旧进程后执行
   `bash deploy/start-navigation.sh`，再用 `bash deploy/check-navigation.sh` 验证。
5. 观察稳定运行一段时间后，再删除机器人上的旧 4 个工作区与旧启动脚本。

### 回滚

- 切换前不要删除旧 4 个工作区；回滚 = 通过 Git 恢复部署脚本/`robot.env` 的旧版本，继续用旧工作区启动。

## 5. 遗留与后续

- [ ] 机器人真机验证 7 个组件的启动、建图/定位/导航/PLC 全链路（本仓库在 Windows 上未构建验证）。
- [ ] 现场 `deploy/robot.env` 更新为 `NAV_WS`（并确认其中其余 `ROBOT_*` 变量不变）。
- [x] 仓库根 `src/`、`scripts/`、`third_party/`、`docs/BUILD.md` 已删除（2026-09-15；内容已被 `blueant_nav_ws/` 取代，Git 历史可回溯）。
- [ ] **提交暂缓（按要求）**：本轮全部改动暂不提交 Git，确认后再提交；`web/` 为嵌套 Git 子仓库（2026-09-15 结构重构后仓库根已上移至 `web/`），
      其脚本/Java 改动需在该子仓库单独提交。
- [ ] `ros2_java_ws` 保持独立（Java 服务专用，不并入本工作区）。
