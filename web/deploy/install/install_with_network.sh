#!/usr/bin/env bash
# =============================================================================
# install_with_network.sh — 有网一键安装（Ubuntu 22.04 amd64）
#
# 用法: sudo bash install_with_network.sh [--update]
#   --update  更新模式：保留数据库，仅重装业务服务
#
# 前置（本脚本不负责）:
#   - ROS2 Humble 与 ~/ros2_java_ws（rosbridge 与 rcljava 由 ROS 端负责）
#
# 本目录必须包含: robot-system_<版本>_amd64.deb
#
# 参照 IPC_Web_service_deb/src/web/deliverable/install_with_network.sh。
# =============================================================================
set -Eeuo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$DIR"

WEB_DIR="/opt/blueant/robot"
WEB_PORT="8088"
ROSBRIDGE_PORT="9090"
UPDATE_MODE=0
for arg in "$@"; do
  [[ "$arg" == "--update" ]] && UPDATE_MODE=1
done

GREEN='\033[0;32m'; YELLOW='\033[1;33m'; RED='\033[0;31m'; NC='\033[0m'
ok()   { echo -e "${GREEN}[OK]${NC}   $*"; }
warn() { echo -e "${YELLOW}[WARN]${NC} $*"; }
die()  { echo -e "${RED}[FAIL]${NC} $*"; exit 1; }

[[ $EUID -eq 0 ]] || die "请用 sudo bash install_with_network.sh 运行"

shopt -s nullglob
APP_DEB=(robot-system_*_amd64.deb)
[[ ${#APP_DEB[@]} -gt 0 ]] || die "未找到 robot-system_*_amd64.deb"
APP_DEB="${APP_DEB[${#APP_DEB[@]}-1]}"

# ---------- 1. 基础运行时（无网安装会跳过这一步，因为依赖包已本地提供） ----------
echo "==> 安装 JDK 17 运行时"
if command -v java >/dev/null 2>&1 && java -version 2>&1 | grep -q '"17\.'; then
  ok "已存在 JDK 17：$(java -version 2>&1 | head -1)"
else
  apt-get update -qq || warn "apt-get update 失败，继续尝试安装"
  apt-get install -y openjdk-17-jre-headless || die "JDK 17 安装失败"
  ok "JDK 17 安装完成"
fi

# ---------- 2. 数据库（更新模式跳过） ----------
if [[ $UPDATE_MODE -eq 1 ]]; then
  echo "==> 更新模式：跳过数据库初始化（保留现有数据）"
else
  echo "==> 确保数据库运行"
  DB_READY=0
  for svc in mysql mariadb; do
    systemctl unmask "$svc" 2>/dev/null || true
    systemctl enable --now "$svc" 2>/dev/null || continue
    if systemctl is-active --quiet "$svc"; then
      DB_READY=1
      ok "数据库已启动 ($svc)"
      break
    fi
  done
  [[ $DB_READY -eq 1 ]] || warn "数据库未能启动，请手工检查（业务服务可能连不上库）"
fi

# ---------- 3. 安装业务服务 ----------
echo "==> 安装 ${APP_DEB}"
dpkg -i "$APP_DEB" || apt-get -f install -y

# ---------- 4. 启动 ----------
echo "==> 启动业务服务"
if [[ -x "${WEB_DIR}/start.sh" ]]; then
  "${WEB_DIR}/start.sh" || warn "start.sh 返回非 0，请查看 ${WEB_DIR}/logs/robot-system.log"
else
  die "未找到 ${WEB_DIR}/start.sh（deb 内容异常）"
fi

# ---------- 5. 验证 ----------
echo ""
echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN}  安装完成$([[ $UPDATE_MODE -eq 1 ]] && echo '（更新模式）')${NC}"
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

# ---------- 6. 前置环境提醒 ----------
echo ""
if [[ ! -r /opt/ros/humble/setup.bash ]]; then
  warn "缺少 /opt/ros/humble/setup.bash：业务服务无法加载 rcljava 原生库，请先装 ROS2 Humble"
fi
if [[ ! -d "$HOME/ros2_java_ws/install" ]]; then
  warn "缺少 ~/ros2_java_ws/install：rcljava 工作空间未就绪"
fi
exit 0
