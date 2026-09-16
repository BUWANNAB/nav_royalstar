#!/usr/bin/env bash
# Stops only process groups recorded by start-navigation.sh.
set -uo pipefail

APP_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
ENV_FILE="${ROBOT_ENV_FILE:-$APP_DIR/deploy/robot.env}"
if [[ -f "$ENV_FILE" ]]; then
    set -a
    source "$ENV_FILE"
    set +a
fi
ROBOT_RUN_DIR="${ROBOT_RUN_DIR:-$APP_DIR/runtime/run}"
status=0
for name in ros2plc vehicle_navigation pcd2pgm tf_to_pose localization build_map_manager livox; do
    pid_file="$ROBOT_RUN_DIR/$name.pid"
    [[ -f "$pid_file" ]] || continue
    pid=$(<"$pid_file")
    if [[ "$pid" =~ ^[0-9]+$ ]] && kill -0 "$pid" 2>/dev/null; then
        kill -INT -- "-$pid" 2>/dev/null || kill -INT "$pid" 2>/dev/null || status=1
        for _ in {1..20}; do
            kill -0 "$pid" 2>/dev/null || break
            sleep 0.25
        done
        if kill -0 "$pid" 2>/dev/null; then
            printf '%s did not stop after SIGINT (PID %s)\n' "$name" "$pid" >&2
            status=1
            continue
        fi
    fi
    rm -f -- "$pid_file"
    printf '%s stopped\n' "$name"
done
exit "$status"
