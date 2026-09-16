#!/usr/bin/env bash
# =============================================================================
# install.sh — 自动安装：优先无网，无网失败后自动切换有网
#
# 用法: sudo bash install.sh
# 本目录需包含: install_no_network.sh, install_with_network.sh
#
# 参照 IPC_Web_service_deb/src/web/deliverable/install.sh 的机制。
# =============================================================================
set -Eeuo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$DIR"

if [[ $EUID -ne 0 ]]; then
  exec sudo bash "$0" "$@"
fi

GREEN='\033[0;32m'; YELLOW='\033[1;33m'; RED='\033[0;31m'; NC='\033[0m'
ok()   { echo -e "${GREEN}[OK]${NC}   $*"; }
warn() { echo -e "${YELLOW}[WARN]${NC} $*"; }

echo -e "${GREEN}==> [1/2] 尝试无网安装 ...${NC}"
if bash install_no_network.sh; then
  ok "无网安装成功"
  exit 0
fi

echo -e "${YELLOW}==> 无网安装失败，尝试有网安装 ...${NC}"
if bash install_with_network.sh "$@"; then
  ok "有网安装成功"
  exit 0
fi

echo -e "${RED}[FAIL]${NC} 无网与有网安装均失败"
exit 1
