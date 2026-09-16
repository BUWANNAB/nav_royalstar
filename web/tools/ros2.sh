#!/bin/bash
# 现场业务启动脚本 + GTK 加载条（无 gnome-terminal，避免盖住加载页）
# 入口：<部署根>/tools/ros2.sh；加载页能力来自 ${BOOT_BRANDING:-$HOME/boot-branding}/boot-splash，
# 缺失时自动降级为无加载页模式（普通启动 + 浏览器打开仍可用）。

set -eo pipefail

APP_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
ENV_FILE="${ROBOT_ENV_FILE:-$APP_DIR/deploy/robot.env}"
if [[ -f "$ENV_FILE" ]]; then
  set -a
  source "$ENV_FILE"
  set +a
fi

ROBOT_JAR="${ROBOT_JAR:-$APP_DIR/RobotSystem-1.0.0.jar}"
ROBOT_CONFIG="${ROBOT_CONFIG:-$APP_DIR/application.yml}"
if [[ ! -r "$ROBOT_CONFIG" ]]; then
  ROBOT_CONFIG="$APP_DIR/backend/src/main/resources/application.yml"
fi
ROBOT_LOG_DIR="${ROBOT_LOG_DIR:-$APP_DIR/runtime/logs}"
ROBOT_RUN_DIR="${ROBOT_RUN_DIR:-$APP_DIR/runtime/run}"
mkdir -p "$ROBOT_LOG_DIR" "$ROBOT_RUN_DIR"

BOOT_BRANDING="${BOOT_BRANDING:-$HOME/boot-branding}"
SPLASH_API="${BOOT_BRANDING}/boot-splash/splash-api.sh"
if [[ -r "$SPLASH_API" ]]; then
  # shellcheck disable=SC1091
  source "$SPLASH_API"
else
  echo "WARN: 未找到 $SPLASH_API，降级为无加载页模式"
  splash_begin() { :; }
  splash_progress() { :; }
  splash_wait_port() { return 0; }
  splash_end() { :; }
  _splash_log() { :; }
  run_browser_kiosk() { return 1; }
fi

mkdir -p /tmp/lanyi-logs

splash_begin
splash_progress 5 "正在启动业务脚本…"

# --- ROS 环境 ---
splash_progress 8 "正在加载 ROS 基础环境…"
ROS_DISTRO="${ROS_DISTRO:-humble}"
source "/opt/ros/${ROS_DISTRO}/setup.bash"
splash_progress 9 "正在加载 ROS 工作空间…"
ROS_JAVA_WS="${ROS_JAVA_WS:-$HOME/ros2_java_ws}"
if [[ -r "$ROS_JAVA_WS/install/setup.bash" ]]; then
  source "$ROS_JAVA_WS/install/setup.bash"
else
  _splash_log "WARN: 缺少 $ROS_JAVA_WS/install/setup.bash（rcljava 未就绪，业务服务可能启动失败）"
fi
splash_progress 10 "ROS 环境已就绪"

# --- 原 sleep 20，期间更新进度与说明 ---
for i in $(seq 1 20); do
  sleep 1
  splash_progress $((10 + i)) "正在等待系统组件就绪 (${i}/20)…"
done
splash_progress 33 "系统组件已就绪"

# --- 源码启动（不打包、不使用 JAR）---
cd "$APP_DIR"
if [[ ! -f "$APP_DIR/backend/pom.xml" ]]; then
  _splash_log "ERROR: 未找到源码工程 pom.xml"
  splash_end
  exit 1
fi
splash_progress 40 "正在准备Java源码运行环境…"
splash_progress 50 "Java源码环境已就绪（不打包JAR）"

# --- 后台启动 Java源码（不弹终端）---
if ! ss -ltn 2>/dev/null | grep -q ':8088 '; then
  splash_progress 60 "正在启动 Java 业务服务…"
  (
    cd "$APP_DIR"
    nohup bash "$APP_DIR/deploy/run-java.sh" \
      >>"$ROBOT_LOG_DIR/robot-system.log" 2>&1 &
    echo $! >"$ROBOT_RUN_DIR/robot-system.pid"
  )
  splash_progress 66 "Java 服务进程已启动"
else
  splash_progress 66 "Java 服务已在运行"
fi

# --- 等待 8088，进度 67%→99%，状态由 splash_wait_port 更新 ---
splash_wait_port 8088 180 67 99 || _splash_log "WARN: 8088 超时，仍继续"

# --- 关加载页 ---
splash_end

# Wayland 开火狐（kiosk 模式）
run_browser_kiosk "http://127.0.0.1:8088" || _splash_log "WARN: 火狐启动失败，见 /tmp/lanyi-logs/firefox.log"

echo "Environment setup complete"
