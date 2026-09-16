#!/usr/bin/env bash
# =============================================================================
# stop.sh — robot-system 停止（安装到 /opt/blueant/robot/stop.sh）
#
# 只停止本服务记录的 pid，不动 ROS / 导航 / 数据库进程。
#
# 参照 IPC_Web_service_deb/tools/web_deb_packaging/scripts/stop.sh
# =============================================================================
set -euo pipefail

APP_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"

ENV_FILE="${ROBOT_ENV_FILE:-$APP_DIR/robot.env}"
[[ -f "$ENV_FILE" ]] || ENV_FILE="$APP_DIR/deploy/robot.env"
if [[ -f "$ENV_FILE" ]]; then
  set -a
  # shellcheck disable=SC1090
  source "$ENV_FILE"
  set +a
fi

ROBOT_RUN_DIR="${ROBOT_RUN_DIR:-$APP_DIR/run}"
PID_FILE="$ROBOT_RUN_DIR/robot-system.pid"

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; NC='\033[0m'

if [[ ! -f "$PID_FILE" ]]; then
  echo -e "${YELLOW}未找到 PID 文件，服务可能未在运行: $PID_FILE${NC}"
  exit 0
fi

pid=$(cat "$PID_FILE" 2>/dev/null || true)
if [[ -z "$pid" ]]; then
  rm -f "$PID_FILE"
  echo -e "${YELLOW}PID 文件为空，已清理${NC}"
  exit 0
fi

if ! ps -p "$pid" > /dev/null 2>&1; then
  rm -f "$PID_FILE"
  echo -e "${YELLOW}进程 $pid 不存在，已清理 PID 文件${NC}"
  exit 0
fi

echo -e "${YELLOW}停止业务服务 (PID: $pid)...${NC}"
kill "$pid" 2>/dev/null || true

for _ in $(seq 1 15); do
  ps -p "$pid" > /dev/null 2>&1 || break
  sleep 1
done

if ps -p "$pid" > /dev/null 2>&1; then
  echo -e "${YELLOW}进程未退出，强制结束${NC}"
  kill -9 "$pid" 2>/dev/null || true
fi

rm -f "$PID_FILE"
echo -e "${GREEN}业务服务已停止${NC}"
