# tools/web_deb_packaging/ —— robot-system 离线交付打包

对应参照项目 `IPC_Web_service_deb` 的 `tools/web_deb_packaging/`。

## 目录结构

```text
tools/web_deb_packaging/
├── build_web_deb.sh                  # 一键构建 deb
├── config/web_packages.conf          # 版本 / 路径 / 包名 / 依赖声明
├── scripts/
│   ├── common.sh                 # 公共函数（日志、require_cmd、make_web_deb）
│   ├── 01_build_web_deb.sh           # 构建 jar → 组装 stage → 打 deb
│   ├── postinst                  # 安装后：仅库不存在时导入 schema；补建目录权限
│   ├── prerm                     # 卸载前：停服务，保留数据
│   ├── start.sh                  # 装到 /opt/blueant/robot/start.sh（source ROS + java -jar）
│   └── stop.sh                   # 装到 /opt/blueant/robot/stop.sh
└── output/                       # 交付包（整目录拷给新机器）
    ├── install*.sh               # 从 deploy/install/ 复制（连同 db/、no_network_packages/）
    ├── robot-system_*.deb        # 构建产物（不入库）
    └── no_network_packages/      # 离线依赖 .deb（不入库，保留 .gitkeep）
```

## 构建

前置条件（缺一不可）：

| 依赖 | 说明 |
|---|---|
| Ubuntu 22.04 amd64 | 目标平台 |
| JDK 17 | 与 `pom.xml` 的编译目标一致 |
| Maven | 构建 jar |
| **ROS2 Humble** | `pom.xml` 依赖 `rcljava` / `std_msgs` 等 ROS2 Java 产物 |
| **`~/ros2_java_ws`** | 提供上述 ROS2 Java 产物（需已 install 到本地 Maven 仓库） |

```bash
cd tools/web_deb_packaging
sudo apt install -y rsync dpkg-dev
./build_web_deb.sh
```

产出 `output/robot-system_<版本>-0jammy_amd64.deb`。

## deb 装了什么

安装到 `/opt/blueant/robot/`：

```
/opt/blueant/robot/
├── RobotSystem-1.0.0.jar    # 可执行 fat jar
├── start.sh                 # source ROS 环境 → java -jar
├── stop.sh                  # 停 pid
├── db/schema.sql            # 表结构（无数据、无 DROP TABLE）
├── web-ui/                  # 前端静态资源（ROBOT_WEB_UI_DIR，serving 由后端 static-locations 挂载）
├── logs/                    # 运行日志
└── run/                     # pid 文件
```

**依赖声明只有 `openjdk-17-jre-headless`。** ROS2 Humble 与 `~/ros2_java_ws` 是
环境前置条件，由部署方（ROS 端）负责 —— 对齐参照项目「deb 仅用于环境已就绪机器」的决策。

## 设计要点

1. **必须带启动脚本，光有 jar 跑不起来。**
   ROS2 Java 的 `rcljava` 依赖原生库 `org_ros2_rcljava_rcl_java__jni`，
   其搜索路径由 ROS 的 `setup.bash` 注入 `LD_LIBRARY_PATH` 后才进入 JVM 的
   `java.library.path`。直接 `java -jar` 必然 `UnsatisfiedLinkError`。

2. **数据库零覆盖。** `postinst` 先查 `db_ant` 是否存在，存在则完全跳过导入。
   升级/重装不会碰现场数据。

3. **`schema.sql` 不含 `DROP TABLE`。** 即使被误执行也不会删表。

4. **只停自己。** `prerm` 与 `stop.sh` 只 kill pid 文件里的进程，
   不动 ROS、导航、数据库进程。

5. **大文件不入库。** `output/*.deb` 与 `no_network_packages/*.deb` 由
   `.gitignore` 排除，目录靠 `.gitkeep` 保留。

6. **前端外置。** jar 不再打包前端；deb 携带 `web-ui/`，`start.sh` 导出 `ROBOT_WEB_UI_DIR` 指过去；
   源码模式由 `deploy/start/run-java.sh` 指向 `<部署根>/frontend`。

## ⚠️ 未验证声明

本打包流程是**按参照项目的成熟模式实现**的，但截至提交时：

- **未在 Ubuntu 上实际执行过 `build_web_deb.sh`**（开发机为 Windows，缺 ROS2 与 Linux 工具链）
- **未实际 `dpkg -i` 安装验证过**
- `mvn package` 产出 `target/RobotSystem-1.0.0.jar` 在本机编译是通过的，
  但打包链路（stage 组装、control 生成、postinst/prerm 行为）**未经端到端验证**

首次在 Ubuntu 上使用前，请按 `UPDATE_GUIDE.md` 的验证章节逐项确认。

## 与源码模式的关系

源码模式（`deploy/start/ros2.sh` → `deploy/start/run-java.sh` → `mvn spring-boot:run`）**保持不变**，
目标机不需要重新同步代码树。两套模式各自独立，详见 `deploy/README.md`。
