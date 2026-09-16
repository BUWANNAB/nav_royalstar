# 新机器装机步骤（Royalstar BlueAnt / 荣事达 AGV）

> 适用：Ubuntu 22.04 amd64 + ROS 2 Humble。仓库 = `nav_royalstar`（单仓库，含 ROS 工作区 + Web 业务系统）。
> 图例：**[已验]** = 已在 WSL/构建机实测通过；**[现场]** = 需在现场机器实测确认。

---

## 0. 拓扑与目录契约

网络（两个独立物理网口 + 局域网）：

| 用途 | 网段 | 工控机（本机） | 对端设备 |
|---|---|---|---|
| 雷达口 | `192.168.2.0/24` | `192.168.2.5` | 雷达 MID-360 `192.168.2.190` |
| PLC 口 | `192.168.8.0/24` | `192.168.8.235`（现场值，任意空闲即可） | PLC `192.168.8.30:502` |
| 局域网/WiFi | 现场自定 | — | 平板/手机访问 `:8088` |

目录契约（部署根 `ROBOT_APP_DIR` = 仓库所在目录，**任意路径**）：

```
<ROBOT_APP_DIR>/                        # 例如 /home/<user>/Royalstar
├── web/                                # 业务系统（后端/前端/部署脚本）
│   ├── backend/  frontend/  tools/
│   └── deploy/{start,stop,check,install,systemd}/
├── blueant_nav_ws/                     # ROS 2 单一工作区
├── pcd/<地图名>/GlobalMap.pcd          # 3D 地图（数据，不在 git）
├── maps/<地图名>/setting/{map.pgm,map.yaml}   # 2D 地图（数据，不在 git）
└── logs/  runtime/{logs,run,files,static-maps}
```

关键环境变量（`web/deploy/robot.env` 统一定义，Java/脚本/ROS 三方共用）：

| 变量 | 默认 | 说明 |
|---|---|---|
| `ROBOT_APP_DIR` | 脚本自定位 | 部署根 |
| `ROBOT_PCD_DIR` / `ROBOT_MAP_DIR` | `<根>/pcd` / `<根>/maps` | 地图目录 |
| `ROBOT_WEB_UI_DIR` | `<根>/frontend`（源码）/ `<安装根>/web-ui`（deb） | 前端静态目录 |
| `NAV_WS` | `$HOME/blueant_nav_ws` | 导航工作区 |
| `ROS_JAVA_WS` | `$HOME/ros2_java_ws` | rcljava 工作区 |
| `BLUEANT_MAP_PCD` | 空 | 开机初始定位地图（完整 PCD 路径） |
| `ROBOT_PLC_HOST` / `ROBOT_PLC_PORT` | `192.168.8.30` / `502` | PLC |
| `ROBOT_DB_*` | root/空@localhost:3306/db_ant | 数据库 |

---

## 1. 获取代码

```bash
git clone https://github.com/BUWANNAB/nav_royalstar.git ~/Royalstar    # 或内网 origin
cd ~/Royalstar
```

- **换行符**：仓库根 `.gitattributes` 已强制脚本/配置为 LF，**在 Linux 上 clone 直接可用**。
  若从 Windows 拷贝（U 盘/rsync），先检查：`file web/deploy/start/run-java.sh` 不应出现 `CRLF`；
  需要时批量修复：`find . -name '*.sh' -exec sed -i 's/\r$//' {} +`
- **执行位**：Windows 拷贝后需补 `chmod +x`（见 §3.4）。

---

## 2. 系统依赖

```bash
sudo apt update
# ROS 2 Humble（若未装，按官方源安装）
sudo apt install -y ros-humble-ros-base ros-humble-rosbridge-suite
# 工具链（python3-vcstool 提供 vcs 命令，rcljava 步骤必需；不随 colcon 一起安装）
sudo apt install -y python3-colcon-common-extensions python3-rosdep python3-vcstool \
                    git rsync curl unzip
# 后端（源码模式需要 Maven；deb 模式只需 JRE/JDK17）
sudo apt install -y openjdk-17-jdk-headless maven mysql-server
# rcljava 自建需要 JDK11（rcljava 编译目标 -source 1.6，JDK11 是最后支持版本）
sudo apt install -y openjdk-11-jdk-headless
```

> **[已验]** `ros-humble-rosbridge-server 2.0.7` 提供 9090 桥；`openjdk-17` + `maven 3.6` + `mysql 8.0.46` 组合可用。
> 若目标机采用 **deb 部署**（§7-B），可省 Maven 与 JDK11，也无需自建 rcljava（直接用随包 jar）。

---

## 3. 两个工作区

### 3.1 导航工作区 `~/blueant_nav_ws`

```bash
cp -a ~/Royalstar/blueant_nav_ws ~/blueant_nav_ws     # 或 ln -s（但 colcon 建议真实目录）
# 也可改 NAV_WS 指向仓库内位置，见 §5
```

### 3.2 rcljava 工作区 `~/ros2_java_ws`（源码模式必需）

Java 后端 `pom.xml` 以 `system` 作用域引用 7 个 JAR：
`rcljava.jar`、`rcljava_common.jar`、`builtin_interfaces_messages.jar`、
`std_msgs_messages.jar`、`geometry_msgs_messages.jar`、`sensor_msgs_messages.jar`、`std_srvs_messages.jar`
—— 必须由本机构建产出（**官方无 Humble 二进制包**）。

**[已验] 构建配方（4 阶段）**：

```bash
export JAVA_HOME=/usr/lib/jvm/java-11-openjdk-amd64     # 编 rcljava 用 JDK11
export PATH="$JAVA_HOME/bin:$PATH"

mkdir -p ~/ros2_java_ws/src && cd ~/ros2_java_ws
# 注意：raw.githubusercontent.com 常不可达，故 .repos 需本地写
cat > ros2_java_humble.repos <<'EOF'
repositories:
  ros2/common_interfaces:  {type: git, url: https://github.com/ros2/common_interfaces.git,  version: humble}
  ros2/rcl_interfaces:     {type: git, url: https://github.com/ros2/rcl_interfaces.git,     version: humble}
  ros2/unique_identifier_msgs: {type: git, url: https://github.com/ros2/unique_identifier_msgs.git, version: humble}
  ros2/rosidl_defaults:    {type: git, url: https://github.com/ros2/rosidl_defaults.git,    version: humble}
  ros2-java/ament_java:    {type: git, url: https://github.com/ros2-java/ament_java.git,     version: main}
  ros2-java/ros2_java:     {type: git, url: https://github.com/ros2-java/ros2_java.git,      version: main}
EOF
vcs import src < ros2_java_humble.repos
source /opt/ros/humble/setup.bash

# ① ament_java 两包
colcon build --symlink-install --packages-select ament_java_resources ament_build_type_gradle
source install/setup.bash
# ② Java 代码生成器（跳过测试依赖，它只存在于不可达的源）
colcon build --symlink-install --packages-up-to rosidl_generator_java --cmake-args -DBUILD_TESTING=OFF
source install/setup.bash
# ③ 重建接口包（让它们重新生成 Java 消息）+ rcljava 栈
rm -rf build/builtin_interfaces build/rcl_interfaces install/builtin_interfaces install/rcl_interfaces
colcon build --symlink-install --packages-up-to rcljava --cmake-args -DBUILD_TESTING=OFF
# ④ 其余消息包（跳过 test_msgs：其测试依赖不可得）
colcon build --symlink-install --cmake-args -DBUILD_TESTING=OFF --packages-skip test_msgs
```

> **若 `vcs` 命令不存在**（`找不到命令 "vcs"`）：`sudo apt install -y python3-vcstool`。
> 装不了（无 apt 源）时等价替代——不用 vcs，手工 clone 同样 6 个仓库到 `src/`：
> ```bash
> cd ~/ros2_java_ws/src
> git clone -b humble https://github.com/ros2/common_interfaces.git
> git clone -b humble https://github.com/ros2/rcl_interfaces.git
> git clone -b humble https://github.com/ros2/unique_identifier_msgs.git
> git clone -b humble https://github.com/ros2/rosidl_defaults.git
> git clone -b main   https://github.com/ros2-java/ament_java.git
> git clone -b main   https://github.com/ros2-java/ros2_java.git
> ```
> 全部需要访问 `github.com`（`vcs import` 同样）。

**验收（7/7 必须全部 OK）**：

```bash
for p in builtin_interfaces/share/builtin_interfaces/java/builtin_interfaces_messages.jar \
         rcljava_common/share/rcljava_common/java/rcljava_common.jar \
         rcljava/share/rcljava/java/rcljava.jar \
         std_msgs/share/std_msgs/java/std_msgs_messages.jar \
         geometry_msgs/share/geometry_msgs/java/geometry_msgs_messages.jar \
         sensor_msgs/share/sensor_msgs/java/sensor_msgs_messages.jar \
         std_srvs/share/std_srvs/java/std_srvs_messages.jar ; do
  [ -f "$HOME/ros2_java_ws/install/$p" ] && echo "OK   $p" || echo "MISS $p"
done
```

### 3.3 构建导航工作区

```bash
cd ~/blueant_nav_ws
sudo BLUEANT_BUILD_JOBS=1 ./scripts/install-vendored-dependencies.sh   # gtsam / Livox-SDK2 → /usr/local（首次）
./scripts/build-workspace.sh                                          # 单线程 colcon（防内存/依赖顺序问题）
source install/setup.bash
```

> `--clean` 可清 build/install/log 后重建；**改变工作区路径后必须 `--clean`**（CMakeCache 写死绝对路径）。
> **[现场]** gtsam/Livox 编译较重（本机未跑，虚拟机资源有限）。

### 3.4 构建后端（源码模式）

```bash
cd ~/Royalstar/web/backend
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64     # 后端用 JDK17（编译目标 17）
mvn -B -DskipTests package        # 产出 target/RobotSystem-1.0.0.jar
```

**[已验]** 11m53s 构建成功，产出 72 MB 可执行 fat jar。
初次构建需下载依赖；若需离线，可预置 `~/.m2/repository`。

```bash
# 脚本执行位（Windows 拷贝场景必做）
chmod +x ~/Royalstar/web/deploy/start/*.sh ~/Royalstar/web/deploy/stop/*.sh \
         ~/Royalstar/web/deploy/check/*.sh ~/Royalstar/web/deploy/install/*.sh \
         ~/Royalstar/blueant_nav_ws/scripts/*.sh
```

---

## 4. 数据库

```bash
sudo systemctl enable --now mysql
# 设 root 密码为 root（与 robot.env 默认一致；也可改 robot.env 用别的密码）
sudo mysql -e "ALTER USER 'root'@'localhost' IDENTIFIED WITH mysql_native_password BY 'root'; FLUSH PRIVILEGES;"

# 导入数据（融合版含建库+17表+数据；deb 安装器则只在库不存在时导结构）
mysql -uroot -proot < ~/Royalstar/web/backend/sql/数据库融合/db_ant_家里测试融合_准备更新_传感器合并版_20260910.sql

# 验收
mysql -uroot -proot -N -e "SELECT COUNT(*) FROM db_ant.t_station;"   # 期望 2025（家用融合库）
mysql -uroot -proot -N -e "SELECT nav_map_pcd_name FROM db_ant.t_param WHERE id=1;"
```

---

## 5. 按机修改的配置（**换机必查**）

| # | 文件 | 行 | 改什么 |
|---|---|---|---|
| 1 | `blueant_nav_ws/src/drivers/livox_ros_driver2/config/MID360_config.json` | 14/16/18/20 | 工控机雷达网口 IP（默认 `192.168.2.5`，**4 处必须一致**） |
| 2 | 同上 | 28 | 雷达自身 IP（默认 `192.168.2.190`） |
| 3 | `blueant_nav_ws/src/hardware/ros2plc/src/ros2plc.cpp` | 11-12 | PLC IP/端口（默认 `192.168.8.30:502`）。**推荐改启动传参而非改源码**，见 §7-D |
| 4 | `web/deploy/robot.env`（由 `.example` 复制） | — | `ROBOT_DB_PASSWORD`（必填）；`NAV_WS`；`BLUEANT_MAP_PCD`；`ROBOT_PLC_HOST/PORT`；`ROS_JAVA_WS` |
| 5 | `web/deploy/systemd/*.service`（仅当启用 systemd 时） | — | `User/Group/WorkingDirectory/ExecStart` 中的用户名与路径（模板默认 `lyrobot004`） |
| 6 | 网口 IP（系统层） | — | 雷达口 `192.168.2.5/24`；PLC 口 `192.168.8.235/24` |

```bash
cp ~/Royalstar/web/deploy/robot.env.example ~/Royalstar/web/deploy/robot.env
chmod 600 ~/Royalstar/web/deploy/robot.env      # 含凭据
```

> 注意：`robot.env` 已被 `.gitignore` 排除，**不会入库**，需在每台机器单独创建。

---

## 6. 数据迁移（**最容易漏**）

地图数据**不在 git 仓库**（只提交了 `.gitkeep` 占位），必须单独搬运：

```bash
# 从旧机拷贝（示例）
rsync -av old:/path/pcd/  ~/Royalstar/pcd/
rsync -av old:/path/maps/ ~/Royalstar/maps/
# 校验
ls ~/Royalstar/pcd/<地图名>/GlobalMap.pcd
ls ~/Royalstar/maps/<地图名>/setting/{map.pgm,map.yaml}
```

- 地图名约定：**PCD 文件夹名 == PGM 文件夹名 == 数据库 `nav_map_pcd_name`**，单层 `[A-Za-z0-9_-]+`；
- 数据也可放别的盘，此时须**同时**改 `robot.env` 的 `ROBOT_PCD_DIR`/`ROBOT_MAP_DIR` 与 `BLUEANT_MAP_PCD`；
- 可选：`~/boot-branding`（开机品牌化 + 加载页 + rosbridge 单元来源）——**不在本仓库**，需单独拷贝（见 §7-C）。

---

## 7. 启动（按依赖顺序）

### A. 数据库
```bash
sudo systemctl enable --now mysql
```

### B. 后端（二选一）

**源码模式**（现场当前在用；需 JDK17 + Maven + ros2_java_ws）：
```bash
bash ~/Royalstar/web/deploy/start/run-java.sh        # 前台；或
~/Royalstar/web/deploy/start/ros2.sh                 # 现场入口（加载页 + 火狐 kiosk）
```

**deb 模式**（目标机无需 Maven）：
```bash
cd ~/Royalstar/web/deploy/install        # 放入 robot-system_*.deb 与 no_network_packages/
sudo bash install.sh                     # 自动：优先无网，失败转有网
sudo /opt/blueant/robot/start.sh
```

### C. rosbridge（9090）—— **必须起，否则前端连不上 ROS**
```bash
# 方式一：systemd（推荐，开机自启）
sudo cp ~/Royalstar/web/deploy/systemd/robot-rosbridge.service /etc/systemd/system/
sudo mkdir -p /var/log/robot
# 按机修改单元里的用户名后：
sudo systemctl daemon-reload && sudo systemctl enable --now robot-rosbridge
# 方式二：临时
source /opt/ros/humble/setup.bash && ros2 launch rosbridge_server rosbridge_websocket_launch.xml
```

### D. 导航链（**一条命令起全部**）
```bash
bash ~/Royalstar/web/deploy/start/start-navigation.sh
```
依次启动：`livox` → `build_map_manager` → `localization` → `tf_to_pose` → `pcd2pgm` → `vehicle_navigation` → `ros2plc`（各带 pid/日志，见 `runtime/`）。

> 若 PLC IP 与默认不同，改 `ros2plc.cpp` 或改用：`ros2 run ros2plc ros2plc --ros-args -p server_ip:=<IP> -p server_port:=502`
> 若雷达网口 IP ≠ `192.168.2.5`，必须先改 `MID360_config.json`（§5-1），否则无点云。

### E. 停止
```bash
bash ~/Royalstar/web/deploy/stop/stop-navigation.sh    # 只停本脚本记录的进程
```

---

## 8. 验证清单

| # | 命令 | 期望 |
|---|---|---|
| 1 | `ss -tlnp \| grep -E ':8088\|:9090'` | 两个端口均 LISTEN |
| 2 | `curl -s -o /dev/null -w '%{http_code}\n' http://localhost:8088/` | `200`（前端首页） |
| 3 | `source ~/blueant_nav_ws/install/setup.bash && ros2 node list` | 含 `/java_ros2_bridge`、`/rosbridge_websocket`、`/lidar_localization`、`/pcd2pgm`、`/build_map_manager`、`/vehicle_navigation` |
| 4 | `ros2 topic hz /livox/lidar` | 持续约 10 Hz（雷达通） |
| 5 | `ros2 param get /ros2plc server_ip` | 实际 PLC IP |
| 6 | `mysql -uroot -proot -e 'SELECT COUNT(*) FROM db_ant.t_station;'` | 有数据 |
| 7 | 网页打开 `http://<工控机IP>:8088` | 首页渲染；地图列表含迁移来的地图 |
| 8 | 页面执行一次「更新地图路径」 | 后端日志出现 `/map_path` 发布；`ros2 topic echo /map_path --once` 可收到完整路径 |
| 9 | `bash ~/Royalstar/web/deploy/check/check-navigation.sh` | 关键节点齐备 |

**[已验]**（WSL 软件级联调）：8088 首页 200、`/java_ros2_bridge` 在线、`/pcd_folder` 与 `/map_path` 动态收发正常、Windows 侧可访问。
**[现场]** 需实测：雷达点云、PLC 读写、底盘运动、地图显示精度。

---

## 9. 常见问题

| 现象 | 原因 / 处理 |
|---|---|
| `bash\r: No such file or directory` | 脚本是 CRLF（Windows 拷贝）。`sed -i 's/\r$//' <file>`；仓库已用 `.gitattributes` 预防 |
| 加载页卡在 66% | **rosbridge 没起来**（最常见）。`systemctl status robot-rosbridge`；日志 `/var/log/robot/rosbridge.log` |
| 页面打开但一直"未连接" | 9090 未监听或防火墙拦截；浏览器无法访问时查 `ROBOT_ROSBRIDGE_URL` |
| `UnsatisfiedLinkError`（rcljava） | 未 `source /opt/ros/humble/setup.bash` 就启动 Java；用 `run-java.sh` 而非 `java -jar` |
| 编译报缺 `rcljava.jar` 等 | `~/ros2_java_ws` 未构建完（§3.2），或 `ROS_JAVA_WS` 指向错误 |
| 无点云 `/livox/lidar` | 雷达网口 IP 与 `MID360_config.json` 不一致（§5-1）；或雷达 IP 变了 |
| PLC 连不上 | `ping 192.168.8.30`；`ros2 param get /ros2plc server_ip`；确认两网口独立 |
| 地图列表空 | 未迁移 `pcd/`、`maps/` 数据（§6） |
| 定位不换图 | 该地图的 `GlobalMap.pcd` 不存在，或 `use_pcd_map=false`；查 `/map_path` 订阅者（应 ≥1） |

---

## 10. 两种运行模式对照

| | 源码模式 | deb 模式 |
|---|---|---|
| 启动 | `deploy/start/ros2.sh` → `run-java.sh` | `/opt/blueant/robot/start.sh` |
| 目标机需要 | JDK17 + **Maven** + ROS2 + ros2_java_ws | JDK17 + ROS2 + ros2_java_ws |
| 前端 | `<部署根>/frontend` | `/opt/blueant/robot/web-ui` |
| systemd | `robot-system-source.service`（装为 `robot-system.service`） | `robot-system.service` |
| 互斥 | 两者都占 8088，**不可同跑** | 同左 |
| 共同依赖 | MySQL、rosbridge(9090)、`~/blueant_nav_ws` | 同左 |

---

## 附：本仓库不包含的内容

| 项 | 说明 |
|---|---|
| `pcd/`、`maps/` 实际数据 | 只有 `.gitkeep` 占位，见 §6 |
| `deploy/robot.env` | 含凭据，不入库，见 §5 |
| `boot-branding/` | 开机品牌化 + 加载页套件，部署在工控机 `~/boot-branding`；rosbridge 单元可改用仓库 `deploy/systemd/` |
| `~/ros2_java_ws` 产物 | 需本机自建（§3.2）；deb 模式随包提供 jar |
| 第三方 `.deb` 离线包 | `deploy/install/no_network_packages/*.deb` 不入库（保留 `.gitkeep`） |
