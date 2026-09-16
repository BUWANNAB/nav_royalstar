#!/usr/bin/env bash
# Foreground Java source entry point; does not package a JAR or start/stop robot nodes.
set -eo pipefail

APP_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="${ROBOT_ENV_FILE:-$APP_DIR/deploy/robot.env}"
if [[ -f "$ENV_FILE" ]]; then
    set -a
    source "$ENV_FILE"
    set +a
fi

ROS_JAVA_WS="${ROS_JAVA_WS:-$HOME/ros2_java_ws}"
ROBOT_CONFIG="${ROBOT_CONFIG:-$APP_DIR/application.yml}"
if [[ ! -r "$ROBOT_CONFIG" ]]; then
    ROBOT_CONFIG="$APP_DIR/backend/src/main/resources/application.yml"
fi
ROBOT_PCD_DIR="${ROBOT_PCD_DIR:-$APP_DIR/pcd}"
ROBOT_MAP_DIR="${ROBOT_MAP_DIR:-$APP_DIR/maps}"
ROBOT_FILES_DIR="${ROBOT_FILES_DIR:-$APP_DIR/runtime/files}"
ROBOT_STATIC_MAPS_DIR="${ROBOT_STATIC_MAPS_DIR:-$APP_DIR/runtime/static-maps}"
ROBOT_LOG_DIR="${ROBOT_LOG_DIR:-$APP_DIR/runtime/logs}"
ROBOT_RUN_DIR="${ROBOT_RUN_DIR:-$APP_DIR/runtime/run}"
ROBOT_WEB_UI_DIR="${ROBOT_WEB_UI_DIR:-$APP_DIR/frontend}"

for required in /opt/ros/humble/setup.bash "$ROS_JAVA_WS/install/setup.bash" "$APP_DIR/backend/pom.xml" "$ROBOT_CONFIG"; do
    if [[ ! -r "$required" ]]; then
        printf 'Missing required file: %s\n' "$required" >&2
        exit 1
    fi
done
command -v java >/dev/null || { echo 'Java is not installed' >&2; exit 1; }
command -v mvn >/dev/null || { echo 'Maven is not installed' >&2; exit 1; }

source /opt/ros/humble/setup.bash
source "$ROS_JAVA_WS/install/setup.bash"
cd "$APP_DIR/backend"
mkdir -p "$ROBOT_PCD_DIR" "$ROBOT_MAP_DIR" "$ROBOT_FILES_DIR" \
    "$ROBOT_STATIC_MAPS_DIR" "$ROBOT_LOG_DIR" "$ROBOT_RUN_DIR"
export ROBOT_PCD_DIR ROBOT_MAP_DIR ROBOT_FILES_DIR ROBOT_STATIC_MAPS_DIR
export ROBOT_LOG_DIR ROBOT_RUN_DIR ROBOT_WEB_UI_DIR
export SPRING_CONFIG_ADDITIONAL_LOCATION="file:$ROBOT_CONFIG"

# spring-boot:run compiles classes as needed but does not create or use a packaged JAR.
exec mvn -DskipTests spring-boot:run
