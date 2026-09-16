#!/bin/bash

APP_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="${ROBOT_ENV_FILE:-$APP_DIR/deploy/robot.env}"
if [[ -f "$ENV_FILE" ]]; then
  set -a
  source "$ENV_FILE"
  set +a
fi
ROBOT_JAR="${ROBOT_JAR:-$APP_DIR/RobotSystem-1.0.0.jar}"
ROBOT_CONFIG="${ROBOT_CONFIG:-$APP_DIR/application.yml}"
if [[ ! -r "$ROBOT_CONFIG" ]]; then
  ROBOT_CONFIG="$APP_DIR/backend/src/main/resources/application.yml"
fi
ROBOT_WEB_UI_DIR="${ROBOT_WEB_UI_DIR:-$APP_DIR/frontend}"
export ROBOT_WEB_UI_DIR
mkdir -p "${ROBOT_LOG_DIR:-$APP_DIR/runtime/logs}"

#sleep 90
# 定义日志文件 return 0:true
LOG_FILE="${ROBOT_LOG_DIR:-$APP_DIR/runtime/logs}/startsh.log"

#function init() {
#    username=$(whoami)
#    # 检查并创建AppData目录
#    if [ ! -d "$HOME/$username/AppData" ]; then
#        mkdir -p "$HOME/$username/AppData"
#    else
#      echo " "
#    fi
#}
#init

function check_java() {
  echo "检查Java环境[1/3]"
  # 动态获取Java路径
  JAVA_BIN=$(command -v java)
  if [ -z "$JAVA_BIN" ]; then
      log_error "Java未安装或未配置环境变量"
      return 1
  fi

  # 验证Java版本兼容性
  JAVA_VERSION=$($JAVA_BIN -version 2>&1 | awk -F '"' '/version/ {print $2}')
  if [[ $JAVA_VERSION != 17* ]]; then
      log_error "检测到Java版本不兼容: $JAVA_VERSION"
      return 1
  fi

  return 0
}


function check_and_start_mysql() {
    echo "检查MySQL环境[2/3]"
    local service_name="mysql"
    local mysql_status=$(systemctl is-active "$service_name" 2>/dev/null)

    if [ $? -eq 0 ]; then
        log_success "MySQL 8 is running."
        return 0
    fi

    echo "MySQL 8 is not running. Attempting to start..."
    if ! systemctl start "$service_name"; then
        log_success "Failed to start MySQL 8 service."
        return 1
    fi

    local timeout=5
    local sleep_interval=1

    for ((i=0; i<timeout; i++)); do
        mysql_status=$(systemctl is-active "$service_name" 2>/dev/null)
        if [ $? -eq 0 ]; then
            log_success "MySQL 8 started successfully. YES"
            return 0
        fi
        sleep $sleep_interval
    done

    log_error "Failed to start MySQL 8 within the given timeout. NO"
    return 1
}

function start_program() {
   # 启动程序的逻辑
    echo "启动程序[3/3]"

    gnome-terminal --tab --title="RosBridge" -- /bin/bash -c "ros2 launch rosbridge_server rosbridge_websocket_launch.xml"

    gnome-terminal --tab --title="RobotSystem" -- /bin/bash -c "cd '$APP_DIR' && exec java -jar '$ROBOT_JAR' '--spring.config.additional-location=file:$ROBOT_CONFIG'"
    return 0
}
2
function check_env() {
    # 逐个执行环境检查和启动逻辑，确保每个步骤都成功
    if ! check_java; then
        return 1
    fi

    if ! check_and_start_mysql; then
        return 1
    fi

    if ! start_program; then
        return 1
    fi

    return 0
}

# 执行环境检查
check_env

# 根据check_env的返回值决定是否继续
if [ $? -eq 0 ]; then
    echo "环境检查成功，程序已启动。"

    #启动浏览器
#    url="http://localhost:8080"
#    chrome="/usr/bin/google-chrome"
#    $chrome --kiosk $url
else
    echo "环境检查失败，请查看日志$LOG_FILE以获取更多详细信息。"
fi
