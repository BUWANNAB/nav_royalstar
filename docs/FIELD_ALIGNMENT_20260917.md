# 2026-09-17 现场版本同步

来源：021 的实际运行源码，笔记本同步时未覆盖其他现场配置。

## 源码修复

| 文件 | 修改 |
| --- | --- |
| web/backend/src/main/java/com/ant/robot/service/ROS2CommunicationService.java | 建图状态接收改用可靠、VOLATILE QoS，与管理节点匹配；经纬度原点为空时仍广播室内位姿 |
| web/backend/src/main/java/com/ant/robot/controller/RouteController.java | 保存及更新室内路线不再强制解析空经纬度原点；XY 的零坐标有效；仅经纬度转换为 XY 时要求配置原点 |
| blueant_nav_ws/src/mapping/build_map_manager/src/build_map_manager_node.cpp | 重复开始建图时，最近两秒仍收到非空点云则重新发送 map_data_ready；尚在启动时发送 starting；无新点云时不伪报就绪 |

## 示例地图

两组示例：`20269171109`、`202609171123`。

- `web/pcd/<名称>/`：GlobalMap、CornerMap、SurfMap、trajectory、transformations PCD。
- `web/maps/<名称>/setting/`：map.pgm、map.yaml。
- map.yaml 中的图片引用保持 `map.pgm` 相对路径。
- 示例仅适用于录制现场；新场地必须重新建图。

## 部署路径

从项目根设置环境，不写死机器人用户名或笔记本目录：

```bash
export ROBOT_APP_DIR="$(pwd)/web"
export ROBOT_PCD_DIR="$ROBOT_APP_DIR/pcd"
export ROBOT_MAP_DIR="$ROBOT_APP_DIR/maps"
export BLUEANT_MAP_PCD="$ROBOT_PCD_DIR/202609171123/GlobalMap.pcd"
```

启动脚本会读取 deploy/robot.env；若该文件另行设置这些变量，应以实际部署配置为准，避免空值覆盖。

## 已验证与限制

- 现场建图管理包编译通过，Java 修复重新编译并启动。
- 连续两次录制请求返回成功；WebSocket 实际收到室内 XY 位姿。
- 定位重启后实际加载第二张地图，状态 active，短时匹配分数约 0.008～0.022。
- 笔记本三个源码及两组地图共 17 个文件，与下载的现场快照逐文件校验一致；未在 Windows 编译 ROS。
- 路线保存修复的最终用户保存结果、长期定位漂移、车辆运行仍需验收。
- 未实现建图与导航完整模式热切换。
- 两秒点云检查不等于全部建图子进程健康检查。
- 雷达安装变换仍需实际标定；当前定位 launch 保留原有 -0.5 米、约 90° 配置，未擅自修改。
- 不同步数据库密码、robot.env、机器授权 ID、编译产物、运行日志或 Maven 全局配置。
- 没有提交或推送 Git。
