#!/usr/bin/env bash
# Read-only readiness check for the current field launcher.
set -o pipefail

ROS_DISTRO="${ROS_DISTRO:-humble}"
source "/opt/ros/$ROS_DISTRO/setup.bash"
nodes=$(timeout 15 ros2 node list 2>/dev/null) || {
    echo 'Unable to read ROS2 node graph' >&2
    exit 1
}

printf '%s\n' "$nodes"
missing=0
for node in lidar_localization pcd2pgm build_map_manager; do
    if ! printf '%s\n' "$nodes" | grep -qx "/$node"; then
        printf 'Missing required node: /%s\n' "$node" >&2
        missing=1
    fi
done
exit "$missing"
