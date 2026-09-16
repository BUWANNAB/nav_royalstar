#!/usr/bin/env bash
# =============================================================================
# 01_build_web_deb.sh — 构建 robot-system deb
#
# 流程：
#   1. 校验 ROS 环境与 JDK/Maven
#   2. mvn -DskipTests package 产出可执行 fat jar（不改动仓库工作树）
#   3. 组装 stage：jar + start.sh/stop.sh + db/schema.sql
#   4. 打包成 robot-system_<版本>_<release>_amd64.deb
#
# 前置：Ubuntu 22.04 + JDK 17 + Maven + ROS2 Humble + ~/ros2_java_ws
#       （pom.xml 依赖 rcljava / std_msgs 等 ROS2 Java 产物，缺一则编译失败）
#
# 参照 IPC_Web_service_deb/tools/web_deb_packaging/scripts/01_build_web_deb.sh
# =============================================================================
set -Eeuo pipefail
# shellcheck disable=SC1091
source "$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/scripts/common.sh"

log_info "=== 01 构建 ${WEB_DEB_NAME} deb ==="
require_cmd mvn java dpkg-deb rsync
require_ros_env

[[ -f "${WEB_SRC_DIR}/pom.xml" ]] || die "未找到 ${WEB_SRC_DIR}/pom.xml"
[[ -d "${WEB_SRC_DIR}/src" ]] || die "未找到 ${WEB_SRC_DIR}/src"

# ---------- 1. 构建 jar ----------
# 在项目目录内构建，产出 target/${JAR_NAME}
# ROS 环境必须先 source，否则 rcljava 依赖解析可能失败
log_info "加载 ROS 环境并构建 jar ..."
# shellcheck disable=SC1091
source /opt/ros/humble/setup.bash
# shellcheck disable=SC1091
source "$HOME/ros2_java_ws/install/setup.bash"

cd "$WEB_SRC_DIR"
log_info "mvn -DskipTests package ..."
mvn -B -DskipTests package || die "Maven 构建失败"

BUILT_JAR="${WEB_SRC_DIR}/target/${JAR_NAME}"
[[ -f "$BUILT_JAR" ]] || die "未找到构建产物: $BUILT_JAR（检查 pom 的 finalName 与版本号）"
log_info "构建产物: $BUILT_JAR ($(du -h "$BUILT_JAR" | cut -f1))"

# ---------- 2. 组装 stage ----------
STAGE="${WORK_ROOT}/stage"
rm -rf "$STAGE"
mkdir -p "$STAGE"

cp -f "$BUILT_JAR" "$STAGE/${JAR_NAME}"
log_info "已放入 jar: ${JAR_NAME}"

# 启停脚本（适配安装路径：脚本内以自身位置推导目录）
for s in start.sh stop.sh; do
  if [ -f "${PROJECT_DIR}/scripts/${s}" ]; then
    cp -f "${PROJECT_DIR}/scripts/${s}" "$STAGE/${s}"
    chmod 755 "$STAGE/${s}"
  else
    die "缺少 ${PROJECT_DIR}/scripts/${s}"
  fi
done

# 数据库结构（供 postinst 在库不存在时导入）
if [ -d "${DELIVERABLE_DIR}/install/db" ]; then
  mkdir -p "$STAGE/db"
  cp -f "${DELIVERABLE_DIR}/install/db/"*.sql "$STAGE/db/" 2>/dev/null || true
  log_info "已放入 db/schema.sql"
else
  log_warn "未找到 ${DELIVERABLE_DIR}/install/db，跳过数据库结构"
fi

# 运行期目录（deb 安装后由 postinst 补建，这里先占位保证结构一致）
mkdir -p "$STAGE/logs" "$STAGE/run"

# 前端静态资源（外置目录：start.sh 导出 ROBOT_WEB_UI_DIR=$APP_DIR/web-ui）
if [ -d "${WEB_ROOT}/frontend" ]; then
  cp -a "${WEB_ROOT}/frontend" "$STAGE/web-ui"
  log_info "已放入 web-ui/（前端静态资源）"
else
  log_warn "未找到 ${WEB_ROOT}/frontend，deb 将不含前端 UI"
fi

# ---------- 3. 打包 ----------
make_web_deb "$STAGE"
log_info "=== 01 完成 ==="
