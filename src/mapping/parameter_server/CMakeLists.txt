cmake_minimum_required(VERSION 3.8)
project(parameter_server)

# Set C++ standard
set(CMAKE_CXX_STANDARD 14)

# Find ROS 2 dependencies
find_package(ament_cmake REQUIRED)
find_package(rclcpp REQUIRED)
find_package(rosidl_default_generators REQUIRED)
find_package(rcl_interfaces REQUIRED)
find_package(std_msgs REQUIRED)

# Generate interfaces
rosidl_generate_interfaces(${PROJECT_NAME}
  "srv/GetParameters.srv"
  DEPENDENCIES rcl_interfaces
)

# Export dependencies for interface generation
ament_export_dependencies(rosidl_default_runtime)

# Add executable
add_executable(parameter_server_node src/parameter_server.cpp)

# 关键：添加生成的接口头文件路径
target_include_directories(parameter_server_node PUBLIC
  ${CMAKE_CURRENT_BINARY_DIR}/rosidl_generator_cpp
)

# 关键：添加这一行 - 链接到生成的接口库
target_link_libraries(parameter_server_node PUBLIC
  ${PROJECT_NAME}__rosidl_typesupport_cpp
)

# 统一使用关键字签名格式
ament_target_dependencies(parameter_server_node PUBLIC
  rclcpp
  rosidl_default_runtime
  std_msgs
)

# MySQL Connector/C++ 链接
target_link_libraries(parameter_server_node PUBLIC
  mysqlcppconn
)

install(TARGETS parameter_server_node
  DESTINATION lib/${PROJECT_NAME}
)

# Export dependencies
ament_export_dependencies(rosidl_default_runtime)
ament_export_dependencies(rclcpp)
ament_export_dependencies(std_msgs)
ament_export_dependencies(rcl_interfaces)

ament_package()
