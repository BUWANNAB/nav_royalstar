# deploy/ —— 现场部署与交付脚本

本目录是 `现场`（荣事达 AGV 业务系统）的**部署与交付区**，
对应参照项目 `IPC_Web_service_deb` 的 `src/web/deliverable/`。

## 目录内容

| 目录 / 文件 | 用途 |
|---|---|
| `start/` | 启动：`ros2.sh`（现场入口，部署为 `<部署根>/ros2.sh`）· `run-java.sh`（源码模式 Java）· `start-navigation.sh`（导航链） |
| `stop/` | 停止：`stop-navigation.sh`（仅停自身记录进程）· `shutdown_ros2_nodes.sh`（进程级 SIGINT） |
| `check/` | 巡检（只读）：`check-navigation.sh`（节点就绪）· `inspect-host.sh` · `inspect-startup.sh` |
| `install/` | 安装交付：`install*.sh` + `db/schema.sql` + `no_network_packages/`（**素材与安装器同目录**；交付时放入 `robot-system_*.deb` 后执行 `sudo bash install.sh`） |
| `systemd/` | systemd 单元模板：`robot-system.service`（deb）· `robot-system-source.service`（源码）· `robot-rosbridge.service`（两模式通用） |
| `robot.env.example` | 部署配置模板；复制为 `robot.env` 后填写（**含凭据，不入库**） |
| `.gitignore` | 排除 `robot.env` |

## 与参照项目的对应关系

| 参照项目 | 本项目 | 说明 |
|---|---|---|
| `src/web/deliverable/` | `deploy/` | 本目录，职责相同 |
| `tools/web_deb_packaging/` | `tools/web_deb_packaging/` | 打包工具区 |
| `/opt/blueant/web/` | `/opt/blueant/robot/` | deb 安装路径 |
| `blueant-web.service` | `robot-system.service` | systemd 单元 |
| `blueant-web` deb 包名 | `robot-system` | dpkg 包名 |

## 两种运行模式（重要）

本项目同时存在两套启动方式，**不要混淆**：

### 1. 源码模式（当前现场在用）

```
deploy/start/ros2.sh  →  deploy/start/run-java.sh  →  source ROS 环境  →  mvn -DskipTests spring-boot:run
```

- 目标机需要 **JDK 17 + Maven + ROS2 Humble + `ros2_java_ws`**
- 每次启动**现场编译**，首次启动较慢
- 代码更新 = 同步源码树
- 由 `deploy/start/ros2.sh` 负责加载条与火狐 kiosk（部署为 `<部署根>/ros2.sh`）

### 2. 打包模式（deb，本次新增）

```
/opt/blueant/robot/start.sh  →  source ROS 环境  →  java -jar RobotSystem-1.0.0.jar
```

- 目标机只需 **JDK 17 + ROS2 Humble + `ros2_java_ws`**，**不需要 Maven**
- 交付物是构建好的 fat jar，离线机器可直接安装
- 打包流程见 `tools/web_deb_packaging/`

**为什么启动脚本必须 `source` ROS 环境**：ROS2 Java 的 `rcljava` 依赖原生库
（`org_ros2_rcljava_rcl_java__jni`），其搜索路径由 ROS 的 `setup.bash` 注入
`LD_LIBRARY_PATH` 后才会进入 JVM 的 `java.library.path`。直接 `java -jar` 会报
`UnsatisfiedLinkError`。`doc/SEPT5_COMPATIBILITY.md` 中「旧根目录 JAR 缺少正确
JNI 启动环境」记录的就是这个问题。

## 注意

- 导航环境为**单一工作区**：`NAV_WS`（默认 `~/blueant_nav_ws`），`start/start-navigation.sh` 只加载它的 `install/setup.bash`；
  旧 → 新对照与机器人上机步骤见父仓库 `docs/SINGLE_WS_PLAN.md`
- `robot.env` 含数据库凭据，由 `.gitignore` 排除，**不要提交**
- `db/schema.sql` 不含 `DROP TABLE`：即使误在已装数据的库上执行，也不会删表
- 现场数据（地图、点云、站点、路线）**不在交付范围内**，由现场机器自身保有
- Web UI / Java 依赖 rosbridge(9090)：用 `deploy/systemd/robot-rosbridge.service` 启用（见文件头注释）；
  老机器若仍由 `~/boot-branding` 提供，可继续使用或迁移为此模板（boot-branding 侧等效入口：
  `sudo bash install.sh --user <用户名> --with-robot-services`，会安装并 enable 两个单元）
