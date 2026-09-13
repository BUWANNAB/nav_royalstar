cmake_minimum_required(VERSION 3.8)
project(pcd2pgm)

## Use C++14
set(CMAKE_CXX_STANDARD 14)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

## Export compile commands for clangd
set(CMAKE_EXPORT_COMPILE_COMMANDS ON)

#######################
## Find dependencies ##
#######################

find_package(ament_cmake_auto REQUIRED)
find_package(rclcpp REQUIRED)
find_package(nav_msgs REQUIRED)
find_package(nav2_msgs REQUIRED)
find_package(OpenCV REQUIRED)
find_package(yaml-cpp REQUIRED)
find_package(rclcpp_action REQUIRED)
find_package(visualization_msgs REQUIRED)
find_package(geometry_msgs REQUIRED)
find_package(tf2 REQUIRED)
find_package(tf2_ros REQUIRED)
find_package(tf2_geometry_msgs REQUIRED)
find_package(cv_bridge REQUIRED)
find_package(std_srvs REQUIRED)
find_package(parameter_server REQUIRED)
ament_auto_find_build_dependencies()

# 确保 std_srvs 包被正确找到
if(NOT std_srvs_FOUND)
  message(FATAL_ERROR "std_srvs package not found")
endif()

# MySQL Connector/C++ 配置 - 更新路径以匹配实际安装位置
find_library(MYSQLCPPCONN_LIB mysqlcppconn PATHS /usr/lib/x86_64-linux-gnu)
find_path(MYSQL_INCLUDE_DIR mysql-cppconn/jdbc/mysql_driver.h PATHS /usr/include)
find_path(CPPCONN_INCLUDE_DIR mysql-cppconn/jdbc/cppconn/statement.h PATHS /usr/include)
find_path(MYSQL_INCLUDE_DIR mysql_driver.h PATHS /usr/include)
find_path(CPPCONN_INCLUDE_DIR statement.h PATHS /usr/include/cppconn)

if(NOT MYSQLCPPCONN_LIB OR NOT MYSQL_INCLUDE_DIR OR NOT CPPCONN_INCLUDE_DIR)
  message(FATAL_ERROR "MySQL Connector/C++ not found. Please install libmysqlcppconn-dev")
endif()

# Include directories - 添加额外的MySQL包含路径
include_directories(
  include
  ${OpenCV_INCLUDE_DIRS}
  ${YAML_CPP_INCLUDE_DIRS}
  ${MYSQL_INCLUDE_DIR}
  ${CPPCONN_INCLUDE_DIR}
  /usr/include/mysql-cppconn
  ${parameter_server_INCLUDE_DIRS}
  /home/lyrobot005/coverage_ws/build/parameter_server/rosidl_generator_cpp
)

# Link directories - 添加MySQL库路径
link_directories(/usr/lib/x86_64-linux-gnu)

###########
## Build ##
###########

ament_auto_add_library(${PROJECT_NAME} SHARED
  DIRECTORY src
)

rclcpp_components_register_node(${PROJECT_NAME}
  PLUGIN pcd2pgm::Pcd2PgmNode
  EXECUTABLE ${PROJECT_NAME}_node
)

# Link libraries for all targets
foreach(target 
  pcd2pgm
)
  target_link_libraries(${target}
    ${OpenCV_LIBRARIES}
    ${YAML_CPP_LIBRARIES}
    ${MYSQLCPPCONN_LIB}
    rclcpp::rclcpp
    parameter_server::parameter_server__rosidl_typesupport_cpp
  )
  
  ament_target_dependencies(${target}
    rclcpp
    nav_msgs
    nav2_msgs
    OpenCV
    yaml-cpp
    rclcpp_action
    visualization_msgs
    geometry_msgs
    tf2
    tf2_ros
    tf2_geometry_msgs
    cv_bridge
    std_srvs
  )
endforeach()

if(BUILD_TESTING)
  # Disable ament_lint_auto to avoid duplicate test names
endif()

#############
## Testing ##
#############

#############
## Install ##
#############

ament_auto_package(INSTALL_TO_SHARE
  config
  launch
  rviz
)