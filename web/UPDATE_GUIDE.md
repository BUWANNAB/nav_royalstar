# robot-system 更新执行指令

> 本文件格式参照 `IPC_Web_service_deb/UPDATE_GUIDE.md`。
> **重要**：文中明确区分「源码模式」（现场当前在用，已有实测记录）与
> 「deb 模式」（本次新增，**尚未验证**），请勿混淆。

---

## 0. 现场当前状态（源码模式）

现场通过 **源码模式** 运行，不产出可交付 artifact：

```
deploy/start/ros2.sh  →  deploy/start/run-java.sh  →  source ROS 环境  →  mvn -DskipTests spring-boot:run
```

目标机需要：**JDK 17 + Maven + ROS2 Humble + `~/ros2_java_ws`**

> **可移植部署（2026-09-15）**：应用目录不再固定 —— 交付包可解压到任意路径；`deploy/` 脚本以自身位置定位应用根
> （也可用 `ROBOT_APP_DIR` 显式覆盖）。下文 `<app-dir>` 指该目录。

| 项 | 值 |
|---|---|
| 应用目录 | 任意路径（`deploy/` 脚本自定位；下文记作 `<app-dir>`） |
| Web 端口 | 8088 |
| rosbridge | 9090（`deploy/systemd/robot-rosbridge.service` 常驻，Web UI/Java 依赖） |
| 数据库 | 本机 `db_ant`（默认 root/root，可由 `robot.env` 覆盖） |
| 配置来源 | `deploy/robot.env`（由 `deploy/robot.env.example` 复制） |
| 运行目录 | `runtime/{logs,run,files,static-maps}` |
| 开机链路 | `~/boot-branding` 的品牌化启动（不在本仓库内）；rosbridge 单元见 `deploy/systemd/robot-rosbridge.service` |

### 0.1 前置检查（只读）

```bash
# 环境采集（只读，输出可用于留档）
bash deploy/check/inspect-host.sh    > host-inventory.txt 2>&1
bash deploy/check/inspect-startup.sh > startup-inventory.txt 2>&1

# 关键项
java -version                       # 期望 17.x
mvn -v                              # 期望 3.x
ls -d /opt/ros/humble               # 期望存在
ls -d ~/ros2_java_ws/install        # 期望存在
ss -tlnp | grep -E ':8088|:9090'    # 期望均监听
df -h /                             # 磁盘余量
```

### 0.2 配置准备

```bash
cd <app-dir>
cp deploy/robot.env.example deploy/robot.env
# 编辑 deploy/robot.env 填写本机配置
chmod 600 deploy/robot.env          # 含凭据，收紧权限
```

`deploy/robot.env` 由 `.gitignore` 排除，**不要提交**。

### 0.3 启动

```bash
cd <app-dir>
bash deploy/start/run-java.sh             # 前台启动（调试用，Ctrl-C 退出）

# 或走现场入口（带加载条 + 火狐 kiosk；部署机为 <app-dir>/ros2.sh）
./deploy/start/ros2.sh
```

### 0.4 验证

```bash
ss -tlnp | grep -E ':8088|:9090'
curl -s -o /dev/null -w 'HTTP %{http_code}\n' http://localhost:8088/
mysql -uroot -proot -e 'SELECT COUNT(*) FROM db_ant.t_station;'
```

### 0.5 更新代码

源码模式下更新 = **同步代码树**，然后重启服务：

```bash
cd <app-dir>
git pull --ff-only                  # 或 rsync 覆盖
# 或由运维侧同步后：
sudo pkill -f 'spring-boot:run'
./deploy/start/ros2.sh
```

> ⚠️ **SSH 别名与实际机器可能不一致。**
> 实测发现：SSH 配置里别名 `lyrobot004` 连到的主机名是 `lyrobot005`，
> 而别名 `lyrobot005` 连接被重置。**动手前务必先确认身份**：
> ```bash
> ssh <别名> "hostname; whoami; hostname -I"
> ```
> 不要凭别名判断是哪台机器。

---

## 1. deb 模式（本次新增，⚠️ 尚未验证）

deb 模式的交付物是**构建好的可执行 jar**，目标机**不需要 Maven**：

```
/opt/blueant/robot/start.sh  →  source ROS 环境  →  java -jar RobotSystem-1.0.0.jar
```

### 1.1 构建（在构建机上进行）

```bash
cd <web 仓库根>/tools/web_deb_packaging
./build_web_deb.sh
```

前置：Ubuntu 22.04 + JDK 17 + Maven + ROS2 Humble + `~/ros2_java_ws`。
产出：`output/robot-system_1.0.0-0jammy_amd64.deb`。

### 1.2 准备交付包

```bash
# 交付包 = output 整目录
# 离线依赖 .deb 需本地备齐（openjdk-17-jre-headless 及其运行时依赖）
ls output/no_network_packages/*.deb
```

### 1.3 上传到目标机

```bash
scp -P <端口> -r output/. <用户>@127.0.0.1:~/robot-system-deliverable/

# 校验完整性
md5sum output/robot-system_1.0.0-0jammy_amd64.deb
ssh <别名> "md5sum ~/robot-system-deliverable/robot-system_1.0.0-0jammy_amd64.deb"
```

### 1.4 安装

```bash
ssh <别名> "cd ~/robot-system-deliverable && echo <密码> | sudo -S bash install.sh"
# 或按网络情况直接选择
#   sudo bash install_no_network.sh
#   sudo bash install_with_network.sh
```

预期输出：
- `prerm`：若服务运行中会自动停止
- `postinst`：`[数据库] db_ant 已存在，跳过导入（升级不覆盖任何数据）` ← **数据安全标志**
- 退出码 0

### 1.5 启动与验证

```bash
ssh <别名> "
  echo <密码> | sudo -S /opt/blueant/robot/start.sh
  ss -tlnp | grep -E ':8088|:9090'
  curl -s -o /dev/null -w 'HTTP %{http_code}\n' http://localhost:8088/
"
```

### 1.6 首次启用前必须验证的项

因为打包链路**未在本机验证过**（开发机为 Windows，无 ROS2/Linux 工具链），
首次在 Ubuntu 上使用前请逐项确认：

| # | 检查项 | 期望 |
|---|---|---|
| 1 | `mvn -DskipTests package` 是否产出 `target/RobotSystem-1.0.0.jar` | 是（本机编译已通过，jar 名需与 `packages.conf` 的 `JAR_NAME` 一致） |
| 2 | `dpkg-deb --build` 是否成功 | 生成 `.deb` |
| 3 | `dpkg -I <deb>` 的 `Depends` | 含 `openjdk-17-jre-headless` |
| 4 | `dpkg -c <deb>` 的内容 | 含 jar、start.sh、stop.sh、db/schema.sql |
| 5 | `dpkg -i` 后 `postinst` 输出 | 未破坏已有 `db_ant` |
| 6 | `start.sh` 启动后 8088 是否监听 | 是 |
| 7 | 日志中是否出现 `UnsatisfiedLinkError` | **否**（若出现，说明 ROS 环境未正确加载） |
| 8 | `/opt/blueant/robot/logs/robot-system.log` | 无致命异常 |

---

## 2. 注意事项

1. **两种模式争夺 8088 端口。** 源码模式的 `deploy/start/ros2.sh` 与 deb 模式的
   `/opt/blueant/robot/start.sh` 不能同时跑。切换前先停掉另一个。

2. **systemd 单元不会自动安装。** `robot-system.service`（deb）与 `robot-system-source.service`
   （源码，`ExecStart=deploy/start/run-java.sh`）都是模板，手工 `cp` 到 `/etc/systemd/system/` 后再 `enable`；
   **两者安装目标同名（`robot-system.service`），同一时刻只启与实际模式对应的那一个**——
   手动入口 `deploy/start/ros2.sh` 与它们也不可同跑（8088 端口互斥）。

3. **rosbridge 与 ROS2 由 ROS 端负责。** 本 deb 只装业务服务；
   rosbridge 单元模板见 `deploy/systemd/robot-rosbridge.service`（手工启用，见文件头注释）；
   `/opt/ros/humble` 与 `~/ros2_java_ws` 缺失会导致启动失败。

4. **数据库零覆盖。** `postinst` 只在 `db_ant` 不存在时导入结构；
   `schema.sql` 不含 `DROP TABLE`，误执行也不会删表。

5. **卸载保留数据。** `prerm` 只停进程，不删 `/opt/blueant/robot` 下的数据与数据库。

6. **不要把数据库密码写入本文件或任何提交的文档。** 凭据只放 `robot.env`。

---

## 3. 相关文档

| 文档 | 内容 |
|---|---|
| `deploy/README.md` | 交付区职责、两种运行模式的差异 |
| `backend/doc/MIGRATION.md` | 换机迁移工作记录与已确认范围 |
| `backend/doc/SEPT5_COMPATIBILITY.md` | 与 9 月 5 日现场版本的兼容清单 |
| `backend/doc/PATH_AUDIT.md` | 硬编码路径审计清单 |
| `tools/web_deb_packaging/README.md` | 打包流程与设计要点 |
