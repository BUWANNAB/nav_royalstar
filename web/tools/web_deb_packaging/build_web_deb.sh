#!/usr/bin/env bash
# =============================================================================
# build_web_deb.sh — 一键构建 robot-system deb
#
# 用法（在 Ubuntu 22.04 + JDK 17 + Maven + ROS2 Humble 机器上）:
#   chmod +x build_web_deb.sh
#   ./build_web_deb.sh
#
# 产出（output/）:
#   robot-system_<版本>_<release>_amd64.deb
#
# 参照 IPC_Web_service_deb/tools/web_deb_packaging/build_web_deb.sh
# =============================================================================
set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "$SCRIPT_DIR/scripts/common.sh"

log_info "========== ${WEB_DESCRIPTION} deb 打包 v${DEB_VERSION} =========="

# ---------- 0. 环境检查 ----------
require_cmd mvn java rsync dpkg-deb
log_info "java: $(java -version 2>&1 | head -1)"
log_info "mvn:  $(mvn -v 2>&1 | head -1)"

# ---------- 1. 构建 ----------
bash "$SCRIPT_DIR/scripts/01_build_web_deb.sh"

# ---------- 2. 交付 ----------
log_info "交付目录: ${OUTPUT_DIR}"
log_info "产物: ${OUTPUT_DIR}/${WEB_DEB_NAME}_${DEB_VERSION}-${DEB_RELEASE}_${DEB_ARCH}.deb"
log_info "========== 全部完成 =========="
