#!/usr/bin/env bash
# =============================================================================
# start.sh — robot-system 启动（安装到 /opt/blueant/robot/start.sh）
#
# 功能：
#   1. 确保数据库运行
#   2. **source ROS2 环境**（关键：rcljava 原生库需要 ROS 注入的库搜索路径）
#   3. 后台启动 java -jar，写 pid 文件，等待 Web 端口就绪
#
# rosbridge(9090) 由 ROS 端负责启动，本脚本不管。
#
# 参照 IPC_Web_service_deb/tools/web_deb_packaging/scripts/start.sh，
# 差异：把 `node index.js` 换成 `java -jar`，并增加 ROS 环境加载。
# =============================================================================
set -euo pipefail

APP_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"

# 配置文件：优先安装目录下的 robot.env，兼容源码布局的 deploy/robot.env
ENV_FILE="${ROBOT_ENV_FILE:-$APP_DIR/robot.env}"
[[ -f "$ENV_FILE" ]] || ENV_FILE="$APP_DIR/deploy/robot.env"
if [[ -f "$ENV_FILE" ]]; then
  set -a
  # shellcheck disable=SC1090
  source "$ENV_FILE"
  set +a
fi

ROS_DISTRO="${ROS_DISTRO:-humble}"
ROS_JAVA_WS="${ROS_JAVA_WS:-$HOME/ros2_java_ws}"
ROBOT_LOG_DIR="${ROBOT_LOG_DIR:-$APP_DIR/logs}"
ROBOT_RUN_DIR="${ROBOT_RUN_DIR:-$APP_DIR/run}"
ROBOT_WEB_UI_DIR="${ROBOT_WEB_UI_DIR:-$APP_DIR/web-ui}"
export ROBOT_WEB_UI_DIR
ROBOT_WEB_PORT="${ROBOT_WEB_PORT:-8088}"
ROBOT_START_TIMEOUT="${ROBOT_START_TIMEOUT:-180}"

JAR_NAME="${ROBOT_JAR_NAME:-RobotSystem-1.0.0.jar}"
JAR_PATH="${ROBOT_JAR:-$APP_DIR/$JAR_NAME}"

PID_FILE="$ROBOT_RUN_DIR/robot-system.pid"
LOG_FILE="$ROBOT_LOG_DIR/robot-system.log"

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; NC='\033[0m'

mkdir -p "$ROBOT_LOG_DIR" "$ROBOT_RUN_DIR"

# ---------- 数据库 ----------
ensure_database() {
  local active
  for svc in mysql mariadb; do
    active=$(systemctl is-active "$svc" 2>/dev/null || true)
    if [[ "$active" == "active" ]]; then
      echo -e "${GREEN}数据库已就绪 ($svc)${NC}"
      return 0
    fi
  done
  for svc in mysql mariadb; do
    echo -e "${YELLOW}数据库未运行，尝试启动 $svc ...${NC}"
    systemctl unmask "$svc" 2>/dev/null || true
    systemctl start "$svc" 2>/dev/null || true
    active=$(systemctl is-active "$svc" 2>/dev/null || true)
    if [[ "$active" == "active" ]]; then
      echo -e "${GREEN}数据库已就绪 ($svc)${NC}"
      return 0
    fi
  done
  echo -e "${YELLOW}警告: 数据库未启动，业务服务可能连不上库${NC}"
  return 0
}

# ---------- ROS 环境 ----------
load_ros_env() {
  if [[ ! -r "/opt/ros/${ROS_DISTRO}/setup.bash" ]]; then
    echo -e "${RED}错误: 缺少 /opt/ros/${ROS_DISTRO}/setup.bash${NC}"
    echo "      业务服务依赖 rcljava 原生库，不加载 ROS 环境会报 UnsatisfiedLinkError。"
    echo "      请先安装 ROS2 ${ROS_DISTRO}。"
    exit 1
  fi
  # shellcheck disable=SC1090
  source "/opt/ros/${ROS_DISTRO}/setup.bash"
  if [[ -r "${ROS_JAVA_WS}/install/setup.bash" ]]; then
    # shellcheck disable=SC1090
    source "${ROS_JAVA_WS}/install/setup.bash"
  else
    echo -e "${YELLOW}警告: 缺少 ${ROS_JAVA_WS}/install/setup.bash（rcljava 工作空间未就绪）${NC}"
  fi
}

# ---------- 业务服务 ----------
start_backend() {
  [[ -f "$JAR_PATH" ]] || { echo -e "${RED}错误: 未找到 $JAR_PATH${NC}"; exit 1; }

  if [[ -f "$PID_FILE" ]]; then
    local old_pid
    old_pid=$(cat "$PID_FILE" 2>/dev/null || true)
    if [[ -n "$old_pid" ]] && ps -p "$old_pid" > /dev/null 2>&1; then
      echo -e "${YELLOW}业务服务已在运行 (PID: $old_pid)${NC}"
      return 0
    fi
    rm -f "$PID_FILE"
  fi

  command -v java >/dev/null 2>&1 || { echo -e "${RED}错误: 未安装 java（需要 JDK 17）${NC}"; exit 1; }

  echo -e "${GREEN}启动业务服务 (java -jar, 端口 ${ROBOT_WEB_PORT})...${NC}"
  cd "$APP_DIR"
  nohup java -jar "$JAR_PATH" >> "$LOG_FILE" 2>&1 &
  echo $! > "$PID_FILE"

  local pid
  pid=$(cat "$PID_FILE")
  if ! ps -p "$pid" > /dev/null 2>&1; then
    echo -e "${RED}业务服务启动失败，请查看 $LOG_FILE${NC}"
    rm -f "$PID_FILE"
    exit 1
  fi
  echo -e "${GREEN}业务服务进程已启动 (PID: $pid)${NC}"
}

# ---------- 等待端口 ----------
wait_port() {
  local i
  for ((i = 1; i <= ROBOT_START_TIMEOUT; i++)); do
    if ss -ltn 2>/dev/null | grep -q ":${ROBOT_WEB_PORT} "; then
      return 0
    fi
    sleep 1
  done
  return 1
}

ensure_database
load_ros_env
start_backend

echo "  等待 ${ROBOT_WEB_PORT} 端口就绪（最多 ${ROBOT_START_TIMEOUT}s）..."
if wait_port; then
  echo -e "${GREEN}${ROBOT_WEB_PORT} 端口已就绪${NC}"
else
  echo -e "${YELLOW}警告: ${ROBOT_WEB_PORT} 端口在 ${ROBOT_START_TIMEOUT}s 内未监听，请查看 $LOG_FILE${NC}"
fi

FIRST_IP=$(hostname -I 2>/dev/null | awk '{print $1}')
echo ""
echo -e "${GREEN}======================================${NC}"
echo -e "${GREEN}   robot-system 已启动${NC}"
echo -e "${GREEN}======================================${NC}"
echo -e "访问地址: ${YELLOW}http://localhost:${ROBOT_WEB_PORT}${NC}"
if [[ -n "$FIRST_IP" ]]; then
  echo -e "局域网:   ${YELLOW}http://${FIRST_IP}:${ROBOT_WEB_PORT}${NC}"
fi
echo -e "日志:     ${YELLOW}${LOG_FILE}${NC}"
echo -e "停止服务: ${YELLOW}sudo ${APP_DIR}/stop.sh${NC}"
