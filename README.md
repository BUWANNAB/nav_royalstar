# Royalstar BlueAnt（荣事达 AGV）交付仓库

本仓库是荣事达 AGV 机器人的交付与开发仓库。

## 仓库布局

| 路径 | 说明 |
|---|---|
| `blueant_nav_ws/` | **唯一的 ROS 2 Humble 导航工作区**：全部导航功能包 + 现场依赖（`third_party/`）+ 构建脚本 + 文档，可整体同步到机器人 `~/blueant_nav_ws` |
| `web/` | 业务 Web/Java 服务仓库根（独立 Git 子仓库；2026-09-15 结构重构：`backend/` Java 源码、`tools/` 工具与打包、`deploy/` 部署与导航启停脚本，已切换为单一 ws 模式） |
| `old/`（已删除） | 旧 4 个工作区快照（`lio_sam_test`、`gtsam_test_ws`、`robot_driver_ws`、`coverage_ws`）已于 2026-09-16 移除；如需对照可从 Git 历史恢复（`git restore -- old`） |
| `docs/` | 方案文档：`SINGLE_WS_PLAN.md`（单一工作区迁移）、`PATH_ALIGNMENT_PLAN.md`（ROS 侧向 Java 前后端路径对齐）、`FRONTEND_BACKEND_ARCHITECTURE.md`（Web 前后端分离架构设计） |
| `SOURCE_MANIFEST.txt` | 各功能包原始来源记录 |

> 迁移过渡副本（原根目录 `src/`、`scripts/`、`third_party/`、`docs/BUILD.md`）已于 2026-09-15 清除，
> 内容统一位于 `blueant_nav_ws/`；如需回溯可从 Git 历史恢复。

## blueant_nav_ws —— 单一工作区结构

```
blueant_nav_ws/
├── src/
│   ├── drivers/        # livox_ros_driver2（Livox MID-360 驱动）
│   ├── mapping/        # lio_sam、build_map_manager、pcd2pgm、parameter_server
│   ├── localization/   # lidar_localization_ros2、ndt_omp_ros2
│   ├── navigation/     # tf_to_pose、vehicle_navigation
│   └── hardware/       # ros2plc、hardware_bind_lib
├── third_party/        # gtsam、Livox-SDK2（构建期依赖，带 COLCON_IGNORE）
├── scripts/            # build-workspace.sh / check-dependencies.sh / install-vendored-dependencies.sh
└── docs/BUILD.md
```

## 导航组件 ↔ 包 对照

| 启动组件 | 包 |
|---|---|
| livox | `livox_ros_driver2` |
| build_map_manager | `build_map_manager` |
| localization | `lidar_localization_ros2`（依赖 `ndt_omp_ros2`） |
| tf_to_pose | `tf_to_pose` |
| pcd2pgm | `pcd2pgm`（依赖 `parameter_server`） |
| vehicle_navigation | `vehicle_navigation`（依赖 `hardware_bind_lib`） |
| ros2plc | `ros2plc` |

所有组件都在同一个工作区（`blueant_nav_ws`）内构建与加载；现场启停见 `web/lyagv_base_web/deploy/start-navigation.sh`。

## 构建

    cd blueant_nav_ws
    sudo BLUEANT_BUILD_JOBS=1 ./scripts/install-vendored-dependencies.sh   # 首次
    source /opt/ros/humble/setup.bash
    rosdep install --from-paths src --ignore-src --skip-keys gtsam -r -y
    ./scripts/build-workspace.sh
    source install/setup.bash

构建产物（`build/`、`install/`、`log/`）不入库。详见 `blueant_nav_ws/docs/BUILD.md` 与 `docs/SINGLE_WS_PLAN.md`。

