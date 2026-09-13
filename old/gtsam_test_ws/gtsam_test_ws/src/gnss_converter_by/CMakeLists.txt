cmake_minimum_required(VERSION 3.8)
project(gnss_converter_by)

if(CMAKE_COMPILER_IS_GNUCXX OR CMAKE_CXX_COMPILER_ID MATCHES "Clang")
  add_compile_options(-Wall -Wextra -Wpedantic)
endif()

# find dependencies
find_package(ament_cmake REQUIRED)
find_package(rclcpp REQUIRED)
find_package(tf2_ros REQUIRED)
find_package(tf2_geometry_msgs REQUIRED)
find_package(geometry_msgs REQUIRED)
find_package(customize_interfaces REQUIRED)
find_package(rclcpp_components REQUIRED)
find_package(nav_msgs REQUIRED)
find_package(std_msgs REQUIRED)
find_package(sensor_msgs REQUIRED)

find_package(novatel_oem7_msgs REQUIRED)

if(BUILD_TESTING)
  find_package(ament_lint_auto REQUIRED)
  # the following line skips the linter which checks for copyrights
  # comment the line when a copyright and license is added to all source files
  set(ament_cmake_copyright_FOUND TRUE)
  # the following line skips cpplint (only works in a git repo)
  # comment the line when this package is in a git repo and when
  # a copyright and license is added to all source files
  set(ament_cmake_cpplint_FOUND TRUE)
  ament_lint_auto_find_test_dependencies()
endif()

# 修正1：统一目标名称（gnss_converter_node -> gnss_converter）[1](@ref)
add_executable(gnss_converter
  src/gnss_converter.cpp
  src/main.cpp
)

# 修正2：为实际构建目标设置包含目录
target_include_directories(gnss_converter PUBLIC
  $<BUILD_INTERFACE:${CMAKE_CURRENT_SOURCE_DIR}/include>
  $<INSTALL_INTERFACE:include>
)

# 修正3：链接依赖到正确目标[1](@ref)
ament_target_dependencies(gnss_converter
  rclcpp
  std_msgs
  sensor_msgs
  tf2_ros
  nav_msgs
  geometry_msgs
  customize_interfaces
  novatel_oem7_msgs
  rclcpp_components
)

# 安装配置

install(TARGETS gnss_converter
  ARCHIVE DESTINATION lib
  LIBRARY DESTINATION lib
  RUNTIME DESTINATION lib/${PROJECT_NAME}  # 关键修改：指定可执行文件到lib/gnss_converter
)

install(DIRECTORY include/
  DESTINATION include
)

# 安装launch文件
install(DIRECTORY launch
  DESTINATION share/${PROJECT_NAME}
)

# 导出配置
ament_export_include_directories(include)
ament_export_libraries(gnss_converter)
ament_export_dependencies(
  rclcpp
  std_msgs
  sensor_msgs
  tf2_ros
  nav_msgs
  geometry_msgs
  customize_interfaces
  novatel_oem7_msgs
)


ament_package()
