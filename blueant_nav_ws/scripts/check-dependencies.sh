#!/usr/bin/env bash
set -euo pipefail

missing=0
check_file() {
  local description="$1"
  shift
  if ! "$@" >/dev/null 2>&1; then
    echo "MISSING: ${description}" >&2
    missing=1
  fi
}

check_file "ROS 2 Humble" test -f /opt/ros/humble/setup.bash
if ! find /usr/local/lib /usr/lib -name 'liblivox_lidar_sdk_shared.so*' -print -quit 2>/dev/null | grep -q .; then
  echo "MISSING: Livox-SDK2 shared library" >&2
  missing=1
fi
if ! find /usr/local/lib /usr/lib -name 'libgtsam.so*' -print -quit 2>/dev/null | grep -q .; then
  echo "MISSING: GTSAM shared library" >&2
  missing=1
fi
command -v colcon >/dev/null 2>&1 || { echo "MISSING: colcon" >&2; missing=1; }
command -v rosdep >/dev/null 2>&1 || { echo "MISSING: rosdep" >&2; missing=1; }

if (( missing )); then
  exit 1
fi
echo "External build prerequisites found."
