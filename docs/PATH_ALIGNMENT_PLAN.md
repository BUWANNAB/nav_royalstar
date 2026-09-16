# 路径对齐方案：ROS 侧向 Java 前后端对齐

> 状态：**部分实施**：去 inHome、保存链绝对路径、`/map_path` 热切换（ROS 订阅 + Java `updateMapPath`）已于 2026-09-15 落地；余项见文末清单。基准 = Java 前后端（`web/backend`，原 `web/lyagv_base_web`，2026-09-15 结构重构），ROS 侧（`blueant_nav_ws`）向其对齐。
> 原则：**Java 的目录布局、环境变量、命名约定、话题名一律不改**；Java 侧只做"自身 bug 修复 + 配置补充"，ROS 侧做适配与消费。
> 相关文档：`web/README.md`（目录/环境变量表）、`web/UPDATE_GUIDE.md`（应用目录自定位、可任意路径）、`docs/SINGLE_WS_PLAN.md`（单一 ws）。

---

## 1. 基准契约（Java 侧，不改）

### 1.1 目录与文件布局

部署根目录 = `ROBOT_APP_DIR`（**可任意路径**：源码模式由 `deploy/` 脚本自定位、也可显式导出覆盖；**不再假定 `~/inHome`**）。
README 中的"项目根目录 `${user.dir}`"与 `ROBOT_APP_DIR` 是同一位置的两种写法（启动脚本 `cd` 到该目录）。

| 数据 | 规则 |
|---|---|
| 3D 点云地图（PCD） | `<ROBOT_PCD_DIR>/<地图名>/GlobalMap.pcd`（地图名 = 单层文件夹名） |
| 2D 栅格地图（PGM） | `<ROBOT_MAP_DIR>/<地图名>/setting/map.pgm` + `map.yaml` |
| 文件服务 / 静态地图 | `<ROBOT_FILES_DIR>` / `<ROBOT_STATIC_MAPS_DIR>`（独立用途，与地图无关） |

### 1.2 环境变量（`deploy/robot.env` → Java 与导航栈共用）

| 变量 | Java 默认 | 现场取值 | ROS 侧现状 |
|---|---|---|---|
| `ROBOT_APP_DIR` | 脚本自定位（任意路径） | `<部署根>` | 可移植部署根（缺省：`deploy/` 脚本位置 / `$HOME`） |
| `ROBOT_PCD_DIR` | `${user.dir}/pcd/` | `<ROBOT_APP_DIR>/pcd` | `build_map_manager` 读取 ✓ |
| `ROBOT_MAP_DIR` | `${user.dir}/maps/` | `<ROBOT_APP_DIR>/maps` | `pcd2pgm` 读取 ✓ |
| `BLUEANT_MAP_PCD` | — | — | `localization` 读取，但**全仓无人写入** ← 缺口 C |

### 1.3 命名约定

- 地图名 = 单层文件夹名，仅 `[A-Za-z0-9_-]+`（Java `MapPaths` 与 pcd2pgm `/map_folder` 都强制）；
- **PCD 文件夹名 == PGM 文件夹名 == DB `nav_map_pcd_name`**（前端注释"PGM和PCD同名"）。

### 1.4 话题契约（Java 已发布 → ROS 消费）

| 话题 | 载荷 | Java 发布点 | ROS 消费点 | 状态 |
|---|---|---|---|---|
| `/buildmap` | `start` / `stop` / `<地图名>` | `ROS2Controller` | `build_map_manager` | ✓ 已对齐 |
| `/buildmap_status` | `idle / mapping / saved:<路径> …` | — | Java 等待并校验文件 | ✓ |
| `/pcd_path_name` | PCD **完整文件路径** | `PCDController`（转 2D 流程） | `pcd2pgm.mapPathCallback` | ✓ |
| `/map_folder` | 单层文件夹名 | `PCDController` | `pcd2pgm.mapFolderCallback` | ✓ |
| `/plan_start` | `5`（触发转 2D） | `ROS2Controller` | `pcd2pgm` | ✓ |
| `/map_path` | PCD **完整文件路径** | `ROS2Controller.setNavMap` | **无订阅者** | ❌ 缺口 A |

### 1.5 关键流程（现状）

1. **建图**：Java `/buildmap` → `build_map_manager` 调 lio_sam 保存 → 期望落盘 `<ROBOT_PCD_DIR>/<名>/GlobalMap.pcd`；Java 校验该文件存在。
2. **转 2D**：Java 发 `/map_folder` + `/pcd_path_name` → `pcd2pgm` 生成 `<ROBOT_MAP_DIR>/<名>/setting/`；Java 校验。
3. **设导航地图**：Java 写 DB `nav_map_pcd_name` + 发 `/map_path`（**空转，无人接收**）。
4. **页面选图**：Java `/update_map_path/<文件夹>` → kill 定位节点 + 改 `localization.yaml`（占位路径，必失败）。

---

## 2. 差距清单

| # | 差距 | 位置 | 影响 | 级别 |
|---|---|---|---|---|
| A | `/map_path` 无订阅者：`set_nav_map` / `update_map_path` 都无法改变定位地图 | `lidar_localization_ros2`（无订阅实现） | UI"设为导航地图"不生效 | P0 |
| B | `update_map_path` 写占位 YAML `/path/to/your/localization.yaml`，且 kill 定位节点后不重启 | `ROS2Controller.java`（`updateMapPath` / `updateYamlMapPath` / `stopLidarLocalizationNode`） | 接口必 500，定位被杀 | P0 |
| C | 定位启动地图只来自 `BLUEANT_MAP_PCD`（无人注入）；写死样例 `test717` **已于 2026-09-15 移除**（空值 + WARN） | `lidar_localization.launch.py:59-63` | 运行前必须显式注入初始地图，否则等 `/map_path` 到达；违背 README"不依赖固定硬件码目录"原则 | P0 |
| D | 保存链路已解耦部署位置（**已实施 2026-09-15**）：`build_map_manager` 原样返回绝对路径（去除 HOME 剥离）；`lio_sam` 绝对路径直接用、仅相对路径才拼 `$HOME` | `mapOptmization.cpp:182-186`、`build_map_manager_node.cpp`（`toLioSamSaveDestination`） | deb 模式（`/opt/blueant/robot`）不再写错 | P1 |
| E | 样例/占位残留：`localization.yaml` 的 `/map/map.pcd`、`lio_sam params` 的 `/3Dmap/`、`/pcd_folder` 无订阅者（`pcd2pgm.yaml` 的 `shuobao-*` **已清理 2026-09-15**） | 各包 | 误导维护、历史包袱 | P2 |

---

## 3. 对齐方案（逐项）

### 方案 A：localization 订阅 `/map_path`，运行时热切换地图 ★核心

现状可利用点：组件里已有两条现成通道——`on_activate`（`use_pcd_map: true` 时加载 `map_path_`）与
`mapReceived`（订阅 `map` 话题后：体素滤波 → `registration_->setInputTarget` → 置 `map_recieved_`）。
`/map_path` 的处理**复用 `mapReceived` 的同一套逻辑**即可：

1. 新增成员与订阅（`lidar_localization_component.hpp/.cpp`）：
   ```cpp
   // initializePubSub() 中增加：
   map_path_sub_ = create_subscription<std_msgs::msg::String>(
     "/map_path", rclcpp::QoS(10),   // 默认 reliable/volatile，必须与 Java 发布器一致
     std::bind(&PCLLocalization::mapPathReceived, this, std::placeholders::_1));
   ```
2. 新增 `mapPathReceived(msg)`：
   - 校验非空、文件可访问（该包 `CMAKE_CXX_STANDARD=14`，**无 `std::filesystem`**；用 `access()` / `ifstream.good()`），不可访问 → `RCLCPP_ERROR` 并保留旧地图（不崩、不停）；
   - `pcl::io::loadPCDFile(new_path, *cloud)` → （GICP 系列先体素滤波）→ `registration_->setInputTarget(...)`；
   - 更新 `map_path_`、`map_recieved_ = true`；
   - 重发 `initial_map`（transient_local，UI/RViz 自动刷新）。
3. 启动加载失败时的行为（配合方案 C）：`on_activate` 里 `loadPCDFile` 失败要打 `RCLCPP_ERROR`（不再静默），
   节点保持运行，等 Java 发 `/map_path` 后可恢复。
4. 线程安全：现状 `mapReceived` 与 `cloudReceived` 之间本来就无锁，本方案与其保持同级；
   建议顺手加一个 `std::mutex registration_mutex_` 保护 `setInputTarget` 与 `align`（可选增强，不阻塞 P0）。
5. QoS 说明（易错点）：durability 必须两侧一致——**单边 `transient_local` 会与 Java 默认 volatile 发布器不匹配、完全收不到消息**。
   P0 用默认 QoS（reliable/volatile）；如需"定位节点后启动也能拿到当前地图"，则**两侧同时**改 `transient_local`（rcljava `QosProfile`）。

### 方案 B：Java 侧修复 `update_map_path`（删 YAML、改为发话题）

`ROS2Controller.updateMapPath` 改为与既有契约一致的最小实现：

```text
1) 校验 <ROBOT_PCD_DIR>/<folder>/GlobalMap.pcd 存在（复用 MapPaths.globalMap）
2) ros2Service.publishMapPathNav(mapPath)        // 已有的 /map_path 发布器
3) 删除：stopLidarLocalizationNode() / updateYamlMapPath() / updateYamlWithSed()
4) 语义定义：
   - update_map_path  = 运行时切换定位地图（不写 DB）
   - set_nav_map      = 运行时切换 + 写 DB nav_map_pcd_name（维持现状）
```

附带两点（不阻塞 P0）：
- （可选 P1）`ROS2CommunicationService` 的 `/map_path` 发布器与 localization 订阅**同时**改为 `transient_local`（rcljava `QosProfile`），实现"后启动也能拿到最近一次地图"；单边改会 QoS 不匹配；
- `drag_move.html` / `pcd-loader` 前端无需改动（调用方式不变）。

### 方案 C：定位地图来源收敛（去样例、可持久化）

三个小步，按需实施；**持久化优先 C3（Java 零改动）**：

- **C1（必做）**：`lidar_localization.launch.py` 保留 `BLUEANT_MAP_PCD` 优先；未设置时打 `RCLCPP_WARN`
  并走中性回退（当前写死的样例地图名属方案 E 待清理项，不再作为默认说明）；
  `deploy/robot.env.example` 增加一行：
  ```bash
  # 初始定位地图（留空=用默认；建议填 "$ROBOT_PCD_DIR/<当前地图>/GlobalMap.pcd"）
  BLUEANT_MAP_PCD=""
  ```
- **C2（可选，需改 Java；"尽量不改"场景不推荐）**：
  - Java：`set_nav_map` / `update_map_path` 成功后把当前地图路径写入 `${ROBOT_RUN_DIR}/current-map.env`
    （内容如 `BLUEANT_MAP_PCD=<ROBOT_PCD_DIR>/<地图名>/GlobalMap.pcd`）；
    `application.yml` 增加 `robot.runtime.run-dir: ${ROBOT_RUN_DIR:${user.dir}/runtime/run}`；
  - `deploy/start-navigation.sh`：在 `source robot.env` 之后追加：
    ```bash
    [[ -f "$ROBOT_RUN_DIR/current-map.env" ]] && source "$ROBOT_RUN_DIR/current-map.env"
    ```
- **C3（推荐，纯 ROS 侧，Java 零改动）**：localization 成功加载地图后自行持久化最近地图
  （`${HOME}/.lidar_localization_last_map`，单文件免建目录）；启动优先级：env `BLUEANT_MAP_PCD` → 最近地图文件 → 中性回退；
  运行中仍由 `/map_path` 热切换。与 C2 二选一。

### 方案 D：保存链路与部署位置解耦（deb 兼容，P1）

让 ROS 保存链**直接采用 Java 配置的 `ROBOT_PCD_DIR` 绝对路径**，不再假设应用在 `$HOME` 下：

- `build_map_manager_node.cpp`：`toLioSamSaveDestination()` 改为直接返回 `real_destination`（删除 HOME 剥离）；
- `lio_sam`（`mapOptmization.cpp` 保存服务）：
  ```cpp
  if (req->destination.empty())        saveMapDirectory = HOME + savePCDDirectory;
  else if (req->destination[0]=='/')   saveMapDirectory = req->destination;        // 绝对路径直接用
  else                                 saveMapDirectory = HOME + req->destination; // 兼容旧相对写法
  ```
- 效果：保存位置恒等于 Java 校验位置 `<ROBOT_PCD_DIR>/<名>/GlobalMap.pcd`；
  源码模式与任意部署路径行为一致（不再依赖 `$HOME` 相对化），deb 模式（`/opt/blueant/robot`）也不再写错。

### 方案 E：清理与文档（P2）

| 文件 | 动作 |
|---|---|
| `localization.yaml` | 删除 `map_path: "/map/map.pcd"` 占位（或注释"由 launch 覆盖"） |
| `pcd2pgm/config/pcd2pgm.yaml` | **已执行(2026-09-15)**：样例值改为与 declare 默认一致的中性值（`$ROBOT_APP_DIR/pcd/point_cloud.pcd` 等） |
| `lio_sam/config/params.yaml` | `savePCDDirectory` 加注释"仅缺省保存目录，正常由 /lio_sam/save_map 指定" |
| `build_map_manager/README.md` | 示例更新为单一 ws（`~/blueant_nav_ws`） |
| `web/backend/doc/`（或本仓库 `docs/`） | 固化"路径契约一页纸"（即本文第 1 节） |

---

## 4. 实施顺序、文件清单与验证

| 阶段 | 方案 | 涉及文件 | 验证方法 |
|---|---|---|---|
| P0-1 | A | `lidar_localization_ros2`（hpp/cpp） | `ros2 topic pub --once /map_path std_msgs/msg/String "{data: '<另一地图>/GlobalMap.pcd'}"` → 定位地图热切换、`initial_map` 刷新、无效路径不崩 |
| P0-2 | B | `ROS2Controller.java` | 室内地图页切换主地图不再 500；定位地图随之切换；`lidar_localization` 进程不再被杀 |
| P0-3 | C1 | `lidar_localization.launch.py`、`deploy/robot.env.example` | robot.env 填写 `BLUEANT_MAP_PCD` 后重启加载指定地图；留空时 WARN + 回退 |
| P1 | D + C3（C2 可选） | `build_map_manager`、`lio_sam`、`lidar_localization_ros2`（最近地图持久化） | 建图保存落点 == Java 校验路径；重启导航栈沿用最近选图；deb 布局同上成立 |
| P2 | E | 各包样例/文档 | 静态走查 + 文档对照 |

**回滚**：各方案相互独立。Java 侧为单文件小改（git 可还原）；ROS 侧为包内改动，重新 `colcon build` 即可回退；配置项（`BLUEANT_MAP_PCD`）为新增，不填不影响现状。

---

## 5. Java 侧改动清单（尽量不改）

原则：Java 契约（目录 / 环境变量 / 命名 / 话题）不动；下列仅为"自身 bug 修复 + 可选项"。

| # | 文件 | 方法 | 必要性 | 改动说明 |
|---|---|---|---|---|
| 1 | `ROS2Controller.java` | `updateMapPath`（连带删除 `updateYamlMapPath` / `updateYamlWithSed` / 对 `stopLidarLocalizationNode` 的调用） | **必须**（不改则每次选图必 500 且杀掉定位节点） | 改为：校验 `<ROBOT_PCD_DIR>/<文件夹>/GlobalMap.pcd` 存在 → `ros2Service.publishMapPathNav(mapPath)` |
| 2 | `ROS2CommunicationService.java` | `/map_path` 发布器 QoS | 可选（运行中切换用默认 QoS 已可用） | 如需"定位节点后启动也能拿到当前地图"，改 `transient_local`（rcljava QosProfile） |
| 3 | `application.yml` + 写入逻辑 | `current-map.env` 持久化 | **可零改动**（改用方案 C3） | 仅在选择 C2 时需要 |
| 4 | 前端（`static/`） | `drag_move.html` / `pcd-loader` | **0 改动** | 方案 A/B 落地后现有调用自然生效 |

**结论：建议 Java 只动 1 个文件、1 个方法（第 1 项）；其余全部由 ROS 侧消化。**
若坚持 Java 零改动：必须同步移除前端对 `update_map_path` 的两处调用（`drag_move.html`），
否则每次选图 500 + 杀定位——比修 1 个方法更麻烦，不推荐。

> 本方案已固化为可复用 skill：`~/.agents/skills/blueant-path-alignment/SKILL.md`（含样例值纪律与实施步骤）。

---

## 6. 风险与待现场确认

1. 现场 `<部署根>/deploy/robot.env` 的实际 `ROBOT_APP_DIR` / `ROBOT_PCD_DIR` / `ROBOT_MAP_DIR`（应为 `<部署根>/{pcd,maps}`；部署根不再固定，由脚本自定位）；
2. 现场当前导航地图实际是哪一张（确认后作为 `BLUEANT_MAP_PCD` 初始值）；
3. rcljava 对 `transient_local`（持久化 QoS）的支持方式（P1 启用，P0 用默认 QoS 即可）；
4. `use_pcd_map` 实际取值（现 yaml 为 `true`；若现场改为走 `map` 话题，方案 A 的订阅依然可用，两者不冲突）；
5. 方案 A 引入的回调换靶标与现有 `mapReceived` 同级并发风险，若现场定位抖动明显则启用可选互斥锁增强。
