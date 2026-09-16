# 9 月 5 日现场版本兼容清单

基线来自工控机旧布局（`/home/lyrobot004/inHome`，历史记录）在 2026-09-05 的实际运行日志；2026-09-15 起部署根改为可配置的 `ROBOT_APP_DIR`，不再固定 `inHome`。新项目继续使用 ROS2 Humble、Java 17 和本机 MySQL。

| 项目 | 9 月 5 日现场行为 | 9 月 9 日迁移代码 | 兼容处理 |
| --- | --- | --- | --- |
| PLC 网络 | PLC 经 RJ45 接入工控机的 `192.168.8.0/24` 网段 | 地址散落在 Java 配置和现场脚本中 | 默认目标统一为 `192.168.8.30:502`，允许环境变量覆盖 |
| PLC 连接所有者 | `ros2plc` 负责底盘 Modbus 通信 | Java 和 `ros2plc` 可能同时连接同一设备 | 默认关闭 Java 旧 PLC 客户端，由 `ros2plc` 独占连接 |
| 底盘启动信号 | `/plc_start` 进入现场驱动的第 8 个保持寄存器 | 新室内页面只发布 `/vehicle_run_star` | 执行任务同时发布 `/plc_start=1`，暂停发布 `0` |
| 路径契约 | Web 用 `${user.home}/inHome/{pcd,maps,src/main/resources/static}` | ROS 端硬编码 `/home/lyrobot00X/...` | 已改为部署根 `ROBOT_APP_DIR`（缺省 `$HOME`）运行时展开，Web 端不改 |
| PLC 数据包 | 写 8 个保持寄存器，第 8 个为启动状态 | 错误版本只写 7 个并尝试线圈写入 | 工控机恢复 9 月 5 日的 8 寄存器协议 |
| ROS2 Java 环境 | 手工从 Maven 开发模式启动时有效 | systemd 直接运行可能缺 JNI | `run-java.sh` 加载 Humble 与 `ros2_java_ws` |
| Java 启动产物 | `<app-dir>/target/classes` 可运行；旧根目录 JAR 缺少正确 JNI 启动环境 | 独立可执行 JAR | 在 Humble 环境中重新构建并由脚本启动 |
| 数据库 | 本机 `db_ant`，用户名和密码写死 | 改为环境变量 | 保留原默认库和用户，密码从 `robot.env` 注入 |
| 地图和 PCD | 多处绝对路径，且含旧用户名 | 改为运行目录和环境变量 | 默认指向部署根 `ROBOT_APP_DIR` 下的现场数据 |
| rosbridge | `ws://localhost:9090` | 可配置 | 默认行为保持一致 |
| Web 端口 | `8088` | 可配置 | 正式环境仍使用 `8088` |
| 位姿显示 | 旧页面坐标处理 | 增加当前位姿显示修正 | 保留新页面实现，需现场地图复核 |
| systemd | 直接运行旧 JAR | 新 service 调用 `deploy/run-java.sh` | 使用新版 service，确保环境与路径完整 |

## 已验证

- Maven 构建成功。
- 候选 JAR 在工控机临时端口 `18088` 启动成功并返回 HTTP 200。
- ROS2 Java 节点成功接收位姿消息。
- Java 旧 PLC 客户端默认关闭，未与 `ros2plc` 抢占 `192.168.8.30:502`。
- 工控机使用 `192.168.8.235/24` 后，`ros2plc` 已连接 PLC 并正常读写。
- 雷达、定位、TF、导航和 `ros2plc` 均已按新启动脚本启动。

## 现场部署要点

- 工控机 PLC 网口配置为 `192.168.8.235/24`，PLC 为 `192.168.8.30`；雷达继续使用独立的 `192.168.2.0/24` 网段。
- `ROBOT_JAVA_PLC_ENABLED=false` 必须保持关闭，除非明确停用 `ros2plc` 并恢复旧 Java 控制链路。
- 每次换机后依次验证网页、点云、定位、路线下发、底盘使能、零速停止和短距离运动。
