#!/usr/bin/env bash
# =============================================================================
# common.sh — web_deb_packaging 公共函数与变量
# 所有子脚本通过 source 引入
#
# 参照 IPC_Web_service_deb/tools/web_deb_packaging/scripts/common.sh
# =============================================================================
set -Eeuo pipefail

# shellcheck disable=SC1091
source "$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/config/web_packages.conf"

# ---------- 日志 ----------
log_info()  { echo -e "\033[32m[INFO]\033[0m  $*"; }
log_warn()  { echo -e "\033[33m[WARN]\033[0m  $*"; }
log_error() { echo -e "\033[31m[ERROR]\033[0m $*"; }
die()       { log_error "$*"; exit 1; }

# ---------- 工具存在性检查 ----------
require_cmd() {
  for c in "$@"; do
    command -v "$c" >/dev/null 2>&1 || die "缺少命令: $c，请先安装。"
  done
}

# ---------- ROS 环境检查 ----------
# 构建 jar 需要 pom.xml 里的 rcljava 等 ROS2 Java 依赖，
# 它们来自 ~/ros2_java_ws 安装到本地 Maven 仓库的产物。
require_ros_env() {
  [[ -r /opt/ros/humble/setup.bash ]] || die "缺少 /opt/ros/humble/setup.bash（需 ROS2 Humble）"
  [[ -d "$HOME/ros2_java_ws/install" ]] || die "缺少 ~/ros2_java_ws/install（需先构建 rcljava 工作空间）"
}

# ---------- deb 打包辅助 ----------
# 将 stage 目录打包成 robot-system deb
# 用法: make_web_deb <stage_dir>
make_web_deb() {
  local stage_dir="$1"
  local deb_root="${WORK_ROOT}/deb_root_${WEB_DEB_NAME}"
  local deb_file="${OUTPUT_DIR}/${WEB_DEB_NAME}_${DEB_VERSION}-${DEB_RELEASE}_${DEB_ARCH}.deb"

  require_cmd dpkg-deb
  [ -d "$stage_dir" ] || die "stage 目录不存在: $stage_dir"

  rm -rf "$deb_root"
  mkdir -p "${deb_root}/DEBIAN"
  mkdir -p "${deb_root}${WEB_INSTALL_DIR}"

  # 拷贝 stage 内容到安装前缀
  cp -a "$stage_dir"/. "${deb_root}${WEB_INSTALL_DIR}/"

  # 生成 control
  {
    echo "Package: ${WEB_DEB_NAME}"
    echo "Version: ${DEB_VERSION}-${DEB_RELEASE}"
    echo "Section: misc"
    echo "Priority: optional"
    echo "Architecture: ${DEB_ARCH}"
    echo "Maintainer: BlueAnt Robotics <support@blueant.local>"
    echo "Installed-Size: $(du -sk "${deb_root}${WEB_INSTALL_DIR}" | cut -f1)"
    echo "Depends: ${WEB_DEPENDS}"
    echo "Description: ${WEB_DESCRIPTION}"
    echo " Installs to ${WEB_INSTALL_DIR}, started by ${WEB_INSTALL_DIR}/start.sh."
    echo " ROS2 Humble and ~/ros2_java_ws are environment prerequisites."
  } > "${deb_root}/DEBIAN/control"

  # postinst / prerm
  for hook in postinst prerm; do
    if [ -f "${PROJECT_DIR}/scripts/${hook}" ]; then
      cp -f "${PROJECT_DIR}/scripts/${hook}" "${deb_root}/DEBIAN/${hook}"
      chmod 755 "${deb_root}/DEBIAN/${hook}"
    fi
  done

  mkdir -p "$OUTPUT_DIR"
  dpkg-deb --build --root-owner-group "$deb_root" "$deb_file" >/dev/null
  log_info "已生成: $deb_file"
}
