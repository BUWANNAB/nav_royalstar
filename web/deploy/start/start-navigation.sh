#!/usr/bin/env bash
# Starts the deployed robot navigation stack from the single blueant_nav_ws. Run as the robot user.
set -eo pipefail

APP_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
ENV_FILE="${ROBOT_ENV_FILE:-$APP_DIR/deploy/robot.env}"
if [[ -f "$ENV_FILE" ]]; then
    set -a
    source "$ENV_FILE"
    set +a
fi

ROS_DISTRO="${ROS_DISTRO:-humble}"
# Single consolidated workspace: every component below loads this one setup.
NAV_WS="${NAV_WS:-$HOME/blueant_nav_ws}"
ROBOT_LOG_DIR="${ROBOT_LOG_DIR:-$APP_DIR/runtime/logs}"
ROBOT_RUN_DIR="${ROBOT_RUN_DIR:-$APP_DIR/runtime/run}"
ROBOT_PCD_DIR="${ROBOT_PCD_DIR:-$APP_DIR/pcd}"
ROBOT_MAP_DIR="${ROBOT_MAP_DIR:-$APP_DIR/maps}"
ROBOT_FILES_DIR="${ROBOT_FILES_DIR:-$APP_DIR/runtime/files}"
ROBOT_STATIC_MAPS_DIR="${ROBOT_STATIC_MAPS_DIR:-$APP_DIR/runtime/static-maps}"

require_file() {
    [[ -r "$1" ]] || { printf 'Missing required file: %s\n' "$1" >&2; exit 1; }
}

require_file "/opt/ros/$ROS_DISTRO/setup.bash"
require_file "$NAV_WS/install/setup.bash"
source "/opt/ros/$ROS_DISTRO/setup.bash"
command -v ros2 >/dev/null || { echo 'ros2 is unavailable after loading ROS' >&2; exit 1; }
mkdir -p "$ROBOT_LOG_DIR" "$ROBOT_RUN_DIR" "$ROBOT_PCD_DIR" "$ROBOT_MAP_DIR" \
    "$ROBOT_FILES_DIR" "$ROBOT_STATIC_MAPS_DIR"

start_component() {
    local name="$1"
    shift 1
    local pid_file="$ROBOT_RUN_DIR/$name.pid"
    local log_file="$ROBOT_LOG_DIR/$name.log"

    if [[ -f "$pid_file" ]]; then
        local old_pid
        old_pid=$(<"$pid_file")
        if [[ "$old_pid" =~ ^[0-9]+$ ]] && kill -0 "$old_pid" 2>/dev/null; then
            local old_command=""
            if [[ -r "/proc/$old_pid/cmdline" ]]; then
                old_command=$(tr '\0' ' ' < "/proc/$old_pid/cmdline")
            fi
            if [[ "$old_command" == *"$name"* ]]; then
                printf '%s already running (PID %s)\n' "$name" "$old_pid"
                return 0
            fi
            printf '%s PID file is stale (PID %s belongs to another process)\n' "$name" "$old_pid"
        fi
        rm -f -- "$pid_file"
    fi

    setsid bash -c 'source "$1"; shift; exec "$@"' _ \
        "$NAV_WS/install/setup.bash" "$@" >>"$log_file" 2>&1 &
    local pid=$!
    printf '%s\n' "$pid" >"$pid_file"
    sleep 1
    if ! kill -0 "$pid" 2>/dev/null; then
        printf '%s failed to start; see %s\n' "$name" "$log_file" >&2
        return 1
    fi
    printf '%s started (PID %s)\n' "$name" "$pid"
}

start_component livox ros2 launch livox_ros_driver2 rviz_MID360_launch.py
sleep "${ROBOT_START_DELAY_SEC:-3}"
start_component build_map_manager ros2 launch build_map_manager build_map_manager.launch.py \
    workspace_setup:="$NAV_WS/install/setup.bash" \
    map_base_dir:="$ROBOT_PCD_DIR"
sleep "${ROBOT_START_DELAY_SEC:-3}"
start_component localization ros2 launch lidar_localization_ros2 lidar_localization.launch.py
sleep "${ROBOT_START_DELAY_SEC:-3}"
start_component tf_to_pose ros2 launch tf_to_pose tf_to_pose.launch.py
sleep "${ROBOT_START_DELAY_SEC:-3}"
start_component pcd2pgm ros2 launch pcd2pgm pcd2pgm_launch.py \
    map_base_dir:="$ROBOT_MAP_DIR"
sleep "${ROBOT_START_DELAY_SEC:-3}"
start_component vehicle_navigation ros2 launch vehicle_navigation vehicle_navigation.launch.py
sleep "${ROBOT_START_DELAY_SEC:-3}"
start_component ros2plc ros2 run ros2plc ros2plc

echo 'Navigation processes started. Use check-navigation.sh to verify ROS readiness.'
