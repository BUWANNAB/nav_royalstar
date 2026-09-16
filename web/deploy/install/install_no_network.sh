#!/usr/bin/env bash
# =============================================================================
# install_no_network.sh — 无网一键安装（Ubuntu 22.04 amd64）
#
# 用法: sudo bash install_no_network.sh
#
# 前置（本脚本不负责，必须已就绪）:
#   - ROS2 Humble           /opt/ros/humble
#   - rcljava 工作空间      ~/ros2_java_ws/install
#   - JDK 17                由 no_network_packages/ 中的 deb 提供，或已装
#
# 本目录必须包含:
#   no_network_packages/*.deb
#   robot-system_<版本>_amd64.deb
#
# 参照 IPC_Web_service_deb/src/web/deliverable/install_no_network.sh。
# =============================================================================
set -Eeuo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$DIR"

WEB_DIR="/opt/blueant/robot"
WEB_PORT="8088"
ROSBRIDGE_PORT="9090"

GREEN='\033[0;32m'; YELLOW='\033[1;33m'; RED='\033[0;31m'; NC='\033[0m'
ok()   { echo -e "${GREEN}[OK]${NC}   $*"; }
warn() { echo -e "${YELLOW}[WARN]${NC} $*"; }
die()  { echo -e "${RED}[FAIL]${NC} $*"; exit 1; }

[[ $EUID -eq 0 ]] || die "请用 sudo bash install_no_network.sh 运行"

# ---------- 0. 前置检查 ----------
shopt -s nullglob
DEBS=(no_network_packages/*.deb)
[[ ${#DEBS[@]} -gt 0 ]] || die "未找到 no_network_packages/*.deb（离线依赖包不完整）"

APP_DEB=(robot-system_*_amd64.deb)
[[ ${#APP_DEB[@]} -gt 0 ]] || die "未找到 robot-system_*_amd64.deb"
# 同名多版本时取字典序最后一个
APP_DEB="${APP_DEB[${#APP_DEB[@]}-1]}"

[[ -r /opt/ros/humble/setup.bash ]] || warn "未找到 /opt/ros/humble/setup.bash（ROS2 未装？业务服务将无法加载 rcljava 原生库）"
[[ -d "$HOME/ros2_java_ws/install" ]] || warn "未找到 ~/ros2_java_ws/install（rcljava 工作空间缺失？）"

echo "==> 安装离线依赖包 (${#DEBS[@]} 个 deb)"
apt-get install -y "${DEBS[@]}" || die "离线依赖安装失败（检查 no_network_packages 是否完整）"

# ---------- 1. 数据库 ----------
echo "==> 确保数据库运行"
DB_READY=0
for svc in mysql mariadb; do
  if systemctl is-active --quiet "$svc" 2>/dev/null; then
    DB_READY=1
    ok "数据库已运行 ($svc)"
    break
  fi
done
if [[ $DB_READY -eq 0 ]]; then
  for svc in mysql mariadb; do
    systemctl unmask "$svc" 2>/dev/null || true
    systemctl enable --now "$svc" 2>/dev/null || continue
    if systemctl is-active --quiet "$svc"; then
      DB_READY=1
      ok "数据库已启动 ($svc)"
      break
    fi
  done
fi
[[ $DB_READY -eq 1 ]] || warn "数据库未启动；业务服务启动后可能连不上库，请手工检查"

# ---------- 2. 安装业务服务 ----------
echo "==> 安装 ${APP_DEB}"
dpkg -i "$APP_DEB" || apt-get -f install -y

# ---------- 3. 启动 ----------
echo "==> 启动业务服务"
if [[ -x "${WEB_DIR}/start.sh" ]]; then
  "${WEB_DIR}/start.sh" || warn "start.sh 返回非 0，请查看 ${WEB_DIR}/logs/robot-system.log"
else
  die "未找到 ${WEB_DIR}/start.sh（deb 内容异常）"
fi

# ---------- 4. 验证 ----------
echo ""
echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN}  安装完成${NC}"
echo -e "${GREEN}========================================${NC}"
if ss -tlnp 2>/dev/null | grep -q ":${WEB_PORT} "; then
  ok "Web 服务 ${WEB_PORT} 端口正常运行"
  echo "   访问: http://localhost:${WEB_PORT}"
else
  warn "${WEB_PORT} 端口未监听，请查看 ${WEB_DIR}/logs/robot-system.log"
fi
if ss -tlnp 2>/dev/null | grep -q ":${ROSBRIDGE_PORT} "; then
  ok "rosbridge ${ROSBRIDGE_PORT} 端口正常运行"
else
  warn "${ROSBRIDGE_PORT} 端口未监听（rosbridge 由 ROS 端负责启动）"
fi
exit 0
