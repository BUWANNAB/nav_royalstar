# 换机迁移工作记录

## 已确认范围

- Ubuntu 22.04、x86_64、ROS2 Humble；Web/Java、ROS2、MySQL 同一台工控机。
- Java、数据库沿用现场版本，不升级。pom 编译目标为 Java 17，现场实际版本需采集。
- 维护 lyagv_base_web；web 保留历史参考，不同步修改。
- 完整机器人自启动链路尚未确认，不自动安装或替换 systemd 服务。

## 第一批改造

- PCDController 与 ROS2Controller 三处点云路径统一读取 pcd.file.path。
- MapPaths 校验单层地图目录名，支持空格与中文。此检查不是符号链接沙箱；现有文件管理接口仍需单独审查。
- ROBOT_PCD_DIR / ROBOT_MAP_DIR 覆盖点云与栅格目录；ROBOT_FILES_DIR / ROBOT_STATIC_MAPS_DIR 覆盖文件服务与静态地图目录。这四个目录用途不同，不能直接合并。
- 数据库地址、用户名、密码和 Web 端口改由部署环境或外部 application.yml 覆盖；源码不保存数据库密码。
- 后端自动打开浏览器改为默认关闭；需要时读取实际 Web 端口并直接调用 xdg-open，不再在源码中保存或向 sudo 传递密码。现场 kiosk 继续由独立启动项负责。
- RosManager.js 使用当前网页主机的 9090 端口。HTTPS 部署必须提供可用的 WSS 服务，目前未验证 TLS/代理部署。
- 移除只适用于 127.0.0.1 的 cookie domain，让浏览器按当前主机处理。
- Maven 的 ros.java.workspace 属性允许覆盖 Java ROS 工作空间。
- parameter_server 使用生成头文件的包相对引用，已有 CMake include 配置提供搜索目录。
- deploy/run-java.sh 为显式外部配置的前台 Java 源码启动入口，不打包 JAR、不启动机器人、不自动启用服务。原现场入口尚未切换。
- 现场导航脚本确认依次启动 Livox、激光定位、TF 转位姿、车辆导航和 PLC，分别加载 lio_sam_test、gtsam_test_ws、robot_driver_ws。新增 start/stop/check-navigation.sh，以配置覆盖工作空间并仅停止自身记录的进程；尚未替换现场脚本或启用自启动。

## 新入口的准备

在构建机已安装 Maven、Java 17 和 ROS Java 依赖后：

```bash
mvn -Dros.java.workspace="$HOME/ros2_java_ws" package
```

此命令仅说明新属性用法；当前 pom 仍默认跳过测试，不能作为验收通过的依据。

准备项目根目录的外部 application.yml，复制 deploy/robot.env.example 为 deploy/robot.env 并填写机器配置。此文件由 Bash source 执行，只使用可信的本地配置，不提交凭据。默认运行数据位于项目根目录的 pcd/、maps/ 和 runtime/ 下，四个目录也可以由 deploy/robot.env 覆盖；不会自动复制旧文件。

确认旧 Java 实例已由现场管理方式停止后，才可测试：

```bash
bash deploy/run-java.sh
```

不要与旧实例同时启动，不要把启动成功视为导航就绪。

## 现场只读采集

在当前能运行 ROS2 命令的终端执行：

```bash
bash deploy/inspect-host.sh > host-inventory.txt 2>&1
```

脚本只读取平台、服务名称、设备链接、工作空间和 ROS 图；不输出进程参数、数据库密码或服务环境变量。输出仍可能含用户名和设备序列号，分享前可自行脱敏。

首轮清单确认 Java、rosbridge 与 MySQL 由 systemd 管理，导航入口可能来自 `starNavgation.sh.desktop`。进一步采集启动链路：

```bash
bash deploy/inspect-startup.sh > startup-inventory.txt 2>&1
```

此脚本过滤服务环境，仅采集服务入口、桌面自启动命令、进程可执行文件/工作目录和候选启动脚本路径。

## 未完成的关键风险

1. 自启动链路、实际启用的 launch、Java/数据库精确版本尚需现场证据。
2. ROS2Controller 的 localization.yaml 仍有占位路径；必须确认实际可写配置和加载它的节点。
3. pcd2pgm 存在旧机配置、输出目录；定位参数存在旧地图引用，需要与 Java 目录同步。**已处理(2026-09-10→2026-09-15)**：运行路径不再固定 `$HOME/inHome`，统一为部署根 `ROBOT_APP_DIR`（缺省回退 `$HOME`）运行时展开，与 Web 端 `ROBOT_PCD_DIR`/`ROBOT_MAP_DIR` 契约对齐；`原版` 备份未动。
4. 两个工作空间有同名驱动/接口包，覆盖顺序与实际串口尚不明确。
5. Java 内 shell 命令、旧启停脚本、模糊进程匹配、文件接口边界尚待专项修复。
6. 库中默认数据库配置和设备地址需现场外部配置覆盖；数据库结构迁移和恢复未验收。
7. ROS Java 原生库、自定义消息、未收录的 LIO-SAM/Livox 工作空间需确定可复现来源。
8. 本地修改位于嵌套 Git 仓库 lyagv_base_web；交付时需同时处理子仓库提交与父仓库指针，不能只提交父仓库。

完整扫描位置见 PATH_AUDIT.md，包含注释与历史代码，需要逐项判定实际使用情况。

## 验证状态

- MapPaths 已用 javac --release 17 编译，验证不同目录、空格、中文以及非法单层目录名。
- 新 Bash 脚本通过 bash -n；Git diff 通过空白检查。
- 当前 Windows 环境未发现 Maven，也没有完成 ROS2 Humble 构建或真机验收。
- 尚未完成换机保证：数据库恢复、全栈构建、节点启动、地图保存/切换、遥控、停车、避障、重启恢复均需后续验证。

## 单一工作区（blueant_nav_ws）

- 导航脚本从 4 个工作区（`lio_sam_test`、`gtsam_test_ws`、`robot_driver_ws`、`coverage_ws`）收敛为单一 `$HOME/blueant_nav_ws`：
  `deploy/start-navigation.sh` 只加载这一个工作区环境；`deploy/robot.env.example` 的 4 个 `*_WS` 变量合并为 `NAV_WS`。
  现场现有 `deploy/robot.env` 需先按新模板替换为 `NAV_WS`，再切换启动方式。
- web 内其他工作区引用同步改为 `~/blueant_nav_ws`：`star_lio_sam.sh`、`ROS2Controller.java`（2 处 `ros2 lifecycle set`）、
  `RobotSystem.java`（1 处 `ros2 node list`，原为 `~/ros2_ws`）。
- 工作区源文件已按模块整理进父仓库 `blueant_nav_ws/`；旧 → 新对照与机器人上机步骤见父仓库 `docs/SINGLE_WS_PLAN.md`。
- 尚未在机器人上构建与验证。
