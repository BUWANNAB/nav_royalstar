# Robot_System（荣事达现场）

AGV 机器人业务系统：Spring Boot 后端 + Web 前端，通过 ROS2 (RCLJava) 与机器人通信，支持地图、路径、任务、点云、录包、重定位以及移动端访问等管理。

**当前流程：准备 ROS2/MySQL/JDK 环境 → 初始化或恢复 db_ant → 启动业务服务 → 浏览器访问 → 按现场设备验证**

环境要求：Ubuntu 22.04 或兼容 ROS2 Humble 的 Linux 环境、JDK17、Maven、MySQL8、ROS2 Humble 和 RosBridge。

---

## 目录结构

```text
├── backend/                  Java 后端（2026-09-15 结构重构自原仓库根；pom.xml 在此）
│   ├── pom.xml               Maven 依赖构建配置 (Spring Boot 3.2.2 + JDK17)
│   ├── src/
│   │   ├── main/java/        后端业务源码 (RESTful API / ROS2通信 / WebSocket / 调度)
│   │   └── main/resources/
│   │       └── application.yml  核心配置文件（环境变量、端口、MySQL、ROS、PLC 和文件路径）
│   ├── sql/                  数据库备份、初始化、融合 SQL 和方案文档
│   └── doc/                  迁移与兼容性文档（MIGRATION / PATH_AUDIT / SEPT5_COMPATIBILITY）
├── frontend/                 前端静态 UI（P0 已外置：jar 不再打包；经 static-locations 挂载，deb 下发到 web-ui/）
├── deploy/                   部署与交付区（按功能分层；详见 deploy/README.md）
│   ├── start/                启动：ros2.sh（现场入口）· run-java.sh · start-navigation.sh
│   ├── stop/                 停止：stop-navigation.sh · shutdown_ros2_nodes.sh
│   ├── check/                巡检（只读）：check-navigation.sh · inspect-host.sh · inspect-startup.sh
│   ├── install/              安装交付：install*.sh + db/schema.sql + no_network_packages/
│   ├── systemd/              systemd 单元模板（robot-system / -source / rosbridge，均需手工启用）
│   ├── robot.env.example     部署配置模板（复制为 robot.env，含凭据不入库）
│   └── .gitignore            排除 robot.env
├── tools/                    工具区
│   └── web_deb_packaging/    deb 打包工具区（详见 tools/web_deb_packaging/README.md）
├── UPDATE_GUIDE.md           更新执行指令（源码模式 + deb 模式）
├── maps/                     栅格地图目录 (.pgm / .yaml)
├── pcd/                      3D 点云地图切片目录
├── runtime/                  运行目录（logs / run / files / static-maps）
└── logs/                     系统运行日志目录
```

> `boot-branding/`（开机全屏 Splash 品牌化套件）**不在本仓库内**，它部署在工控机的
> `~/boot-branding`，由 `ros2.sh` 通过 `BOOT_BRANDING` 变量引用。

---

## ① 安装

### 1. 安装依赖

```bash
# JDK17
sudo apt update
sudo apt install openjdk-17-jdk

# MySQL8
sudo apt install mysql-server
sudo mysql_secure_installation

# ROS2 Humble RosBridge
sudo apt-get install ros-humble-rosbridge-suite
```

启动前加载 ROS2 环境：

```bash
source /opt/ros/humble/setup.bash
source ~/ros2_java_ws/install/setup.bash
```

### 2. 初始化数据库

```bash
mysql -uroot -p < sql/荣事达家里.sql
```

数据库名为 `db_ant`。数据库用户名、密码和 JDBC 地址由 `ROBOT_DB_USERNAME`、`ROBOT_DB_PASSWORD`、`ROBOT_DB_URL` 配置，不在 README 中固定真实密码。

注意：SQL 转储中包含建库和 `DROP TABLE` 语句。任何导入前必须先对当前 `db_ant` 做带时间戳的备份，并确认连接的是目标 MySQL 实例。

### 3. 打包（可选，已有 jar 可跳过）

```bash
mvn -DskipTests package
```

## ② 启动

```bash
./ros2.sh
```

`ros2.sh` 步骤：加载 ROS2 Humble 和 Java ROS2 工作空间 → 等待系统组件 → 通过 `mvn spring-boot:run` 启动 Java 源码（不打包、不使用 JAR）→ 等待 `8088` 端口 → 尝试打开浏览器。后端默认端口 **8088**。

`start.sh` 是历史启动脚本，内部仍存在固定的 JAR 和配置路径。除非已按当前现场目录修改并验证，否则不建议使用它作为主要启动入口。

## ③ 查看/验证效果

浏览器打开 **http://127.0.0.1:8088**，登录后进入主界面，可查看地图、机器人实时位姿、任务状态等。

### 家里临时免登录测试

为方便家里联调，前端支持临时免登录开关：

- 静态页面预览服务（`8080` 端口）默认免登录，访问 `http://127.0.0.1:8080/` 即可进入主界面。
- 访问 `http://127.0.0.1:8080/?skipLogin=0`，可临时清除免登录标记并检查登录页。
- 后端正式服务（`8088` 端口）仍需显式访问 `http://127.0.0.1:8088/?skipLogin=1` 才启用临时免登录。
- 静态页面点击“退出登录”会回到免登录主界面；正式服务点击后返回登录页。

该开关只用于家里测试，不删除正式登录页面，也不应在现场生产环境启用。

### `/path_point` 硬件协议与路线字段

后端发布到 ROS2 `/path_point` 的消息类型为 `std_msgs/msg/Float64MultiArray`，数据必须满足 `1 + 9 × N`：

```text
data[0] = N
data[1 + 9*i] = x 坐标（m）
data[2 + 9*i] = y 坐标（m）
data[3 + 9*i] = 目标航向角（rad）
data[4 + 9*i] = 站点/路径点 ID
data[5 + 9*i] = 点速度（m/s，负数表示倒车）
data[6 + 9*i] = 运行模式（0-追踪，1-自转）
data[7 + 9*i] = 定位模式
data[8 + 9*i] = 预留值，固定为 0
data[9 + 9*i] = 作业时长（s）
```

数据库 `t_route_detail` 中，`direction` 以度保存，发布时转换为弧度；`runmode`、`position`、`stopTime` 分别对应协议中的 `data[6 + 9*i]`、`data[7 + 9*i]`、`data[9 + 9*i]`。`lanechange` 和 `stop` 仍保留给业务/PLC逻辑，不占用协议预留字段。三份 SQL 备份及融合 SQL 的建表语句已补充字段注释，便于后续维护。

## ④ 访问

浏览器打开 **http://127.0.0.1:8088**（远程访问替换为工控机实际 IP）。

### 手机/平板访问

1. 手机或平板与工控机接入同一局域网。
2. 在移动端浏览器访问 `http://<工控机局域网IP>:8088`，推荐横屏使用地图和路线操作页面。
3. 确认工控机防火墙允许局域网访问 `8088`；浏览器需要直连 ROS Bridge 时，还需允许 `9090`。

前端 REST 和业务 WebSocket 默认跟随当前网页的主机与端口，ROS Bridge 默认连接当前网页主机的 `9090` 端口。特殊部署可在浏览器控制台执行
`localStorage.setItem('rosbridgeUrl', 'ws://实际ROS主机:9090')` 后刷新页面覆盖默认地址。

---

## 常用操作

| 操作 | 命令 |
|------|------|
| 推荐现场启动 | `bash deploy/start/ros2.sh`（部署机为 <部署根>/ros2.sh） |
| Java源码启动 | `bash deploy/start/run-java.sh` |
| 手动构建 | `mvn -DskipTests package` |
| 建图（LIO-SAM） | UI「开始建图」→ `/buildmap`（build_map_manager 链路） |
| 关闭 ROS2 节点 | `bash deploy/stop/shutdown_ros2_nodes.sh` |
| 重定位（初始位姿） | UI 地图页选点 / RViz「2D Pose Estimate」 |
| 查看服务端口 | `ss -tlnp \| grep -E '8088|9090'` |
| 查看 ROS2 节点 | `ros2 node list` |
| 自定义配置启动 | `ROBOT_CONFIG=/path/application.yml bash deploy/start/run-java.sh` |
| 部署开机全屏与自启 | `cd boot-branding && sudo ./install.sh` |

## 常见问题

- **start.sh 报 `Syntax error: "(" unexpected`**：用 bash 运行（`bash start.sh`），不要用 sh。
- **数据库连不上**：确认 MySQL 已启动（`systemctl status mysql`），数据库名为 `db_ant`，并检查 `ROBOT_DB_URL`、`ROBOT_DB_USERNAME` 和 `ROBOT_DB_PASSWORD`。
- **前端页面打不开**：确认后端已启动且端口 8088 未被占用（`ss -tlnp | grep 8088`）。
- **移动端打不开**：确认手机与工控机在同一局域网，访问工控机局域网 IP，并确认防火墙允许 `8088`。
- **ROS Bridge 连接失败**：确认 RosBridge 已启动并监听 `9090`，必要时检查 `ROBOT_ROSBRIDGE_URL` 或浏览器中的 `rosbridgeUrl`。
- **地图为空或路径异常**：检查 `ROBOT_STATIC_MAPS_DIR`、`ROBOT_MAP_DIR`、`ROBOT_PCD_DIR`，并确认地图文件权限可读。
- **PLC 不可用**：检查 `ROBOT_PLC_HOST`、`ROBOT_PLC_PORT` 和 `ROBOT_JAVA_PLC_ENABLED`，再验证网络连通性。
- **手动替换 jar 内静态文件**：`jar -xvf` 解压 → 替换 `static/` 下文件 → `jar -cvf` 重新打包。
- **Python 输出不实时**：加 `-u` 参数（`python3 -u test.py > output.txt 2>&1`）。

---

## 配置环境变量

核心配置文件为 `backend/src/main/resources/application.yml`。未设置环境变量时，使用下列默认值：

| 环境变量 | 用途 | 默认值 |
|---|---|---|
| `ROBOT_DB_URL` | MySQL JDBC 地址 | `jdbc:mysql://localhost:3306/db_ant?serverTimezone=Asia/Shanghai` |
| `ROBOT_DB_USERNAME` | 数据库用户名 | `root` |
| `ROBOT_DB_PASSWORD` | 数据库密码 | 空 |
| `ROBOT_WEB_PORT` | Web 服务端口 | `8088` |
| `ROBOT_ROSBRIDGE_URL` | ROS Bridge WebSocket 地址 | `ws://localhost:9090` |
| `ROBOT_PLC_HOST` | PLC 地址 | `192.168.8.30` |
| `ROBOT_PLC_PORT` | Modbus TCP 端口 | `502` |
| `ROBOT_JAVA_PLC_ENABLED` | 是否启用 Java PLC 客户端 | `false` |
| `ROBOT_BROWSER_AUTO_OPEN` | 后端是否自动打开浏览器 | `false` |
| `ROBOT_FILES_DIR` | 文件服务根目录 | `${user.dir}/runtime/files` |
| `ROBOT_STATIC_MAPS_DIR` | 静态地图目录 | `${user.dir}/runtime/static-maps` |
| `ROBOT_PCD_DIR` | PCD 点云目录 | `${user.dir}/pcd/` |
| `ROBOT_MAP_DIR` | PGM/YAML 地图目录 | `${user.dir}/maps/` |
| `ROBOT_APP_DIR` | 部署根（任意路径；源码=脚本自定位，deb=`/opt/blueant/robot`） | 由 `deploy/` 脚本位置推导 |
| `BLUEANT_MAP_PCD` | 初始定位地图（PCD 完整路径；导航栈读取） | 空（等待 `/map_path` 热切换） |

示例：

```bash
export ROBOT_DB_USERNAME=root
export ROBOT_DB_PASSWORD='现场数据库密码'
export ROBOT_WEB_PORT=8088
export ROBOT_ROSBRIDGE_URL='ws://127.0.0.1:9090'
export ROBOT_PLC_HOST='192.168.8.30'
export ROBOT_JAVA_PLC_ENABLED=false
```

文件服务、地图和点云路径默认基于项目根目录 `${user.dir}`，也可以通过环境变量覆盖，不依赖固定的硬件码目录。

## SQL 资料与数据库融合原则

数据库资料统一放在 `sql/` 下：

```text
sql/
├── 荣事达家里.sql                         家里测试数据库备份，作为家里测试的数据基础
├── 荣事达现场.sql                         现场数据库备份，用于回退和结构对照
├── 数据库融合/
│   └── db_ant_家里测试融合_准备更新_传感器合并版_20260910.sql
│                                           家里测试用融合准备 SQL
├── 水质检测/
│   ├── db_ant_before_refactor.sql           水质检测数据库原始资料
│   ├── pose_route_init.sql                  水质检测路径/位姿初始化资料
│   └── t_sensor_example.sql                 水质传感器示例资料
└── 融合计划/
    └── 数据库融合方案_现场主库_家里测试.md   融合方案和操作记录
```

融合约定：

1. 荣事达已有的地图、路线和任务功能以荣事达数据库结构为准。
2. 只引入水质检测特有的传感器业务，不替换荣事达已有功能。
3. 水质检测功能的表名和字段名以 `sql/水质检测/` 中的实际结构为准。
4. `sql/荣事达家里.sql` 是家里测试的数据来源，`sql/荣事达现场.sql` 仅作为现场备份和对照。
5. 导入前必须先备份当前 `db_ant`；导入后检查表数量、关键表结构、地图、路径和传感器数据。

## Git 分支状态

- `develop-mobile-web`：当前移动端与现场功能的集成测试分支，家里测试优先使用此分支。
- `master`：正式发布和现场部署分支，待家里测试、硬件联调和回归确认后再合入。
- 当前已推送的最新提交：`e4074bdf84d0f4d3072398de95b62f8f5e322496`。

提交代码前建议执行：

```bash
git diff --check
git status --short
mvn -DskipTests package
```

本地构建通过不代表已经完成现场验证；实际机器人通信、ROS2 节点、PLC、MySQL 连接和浏览器移动端访问仍需在目标机器上逐项确认。
