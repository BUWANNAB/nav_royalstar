cmake_minimum_required(VERSION 3.8)
project(gurad_handle_ros2)

if(CMAKE_COMPILER_IS_GNUCXX OR CMAKE_CXX_COMPILER_ID MATCHES "Clang")
  add_compile_options(-Wall -Wextra -Wpedantic)
endif()

# find dependencies
find_package(ament_cmake REQUIRED)
find_package(rclcpp REQUIRED)
find_package(std_msgs REQUIRED)
# find_package(boost_system REQUIRED)
# find_package(boost_asio REQUIRED)
find_package(geometry_msgs REQUIRED)
find_package(Boost REQUIRED COMPONENTS system)
find_package(sensor_msgs REQUIRED)
find_package(customize_interfaces REQUIRED)

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

# Include directories
include_directories(
  include
  ${Boost_INCLUDE_DIRS}
)

# Add executable
add_executable(de43_xs
  src/main.cpp
  src/de43_xs.cpp
)
ament_target_dependencies(de43_xs rclcpp std_msgs sensor_msgs customize_interfaces Boost)

# Link libraries
target_link_libraries(de43_xs
  ${customize_interfaces_LIBRARIES}  # 使用自动生成的变量
  ${rclcpp_LIBRARIES}
  ${std_msgs_LIBRARIES}
  ${sensor_msgs_LIBRARIES}
  ${Boost_LIBRARIES}
)

# Install targets
install(TARGETS de43_xs
  DESTINATION lib/${PROJECT_NAME})

# Install include directory
install(DIRECTORY include/
  DESTINATION include/${PROJECT_NAME})

# Add ament export dependencies
ament_export_dependencies(rclcpp)
ament_export_dependencies(std_msgs)
ament_export_dependencies(Boost)

# 添加头文件目录
target_include_directories(de43_xs PUBLIC
  $<BUILD_INTERFACE:${CMAKE_CURRENT_SOURCE_DIR}/include>
  $<INSTALL_INTERFACE:include>)

# Install launch files if any
if(EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/launch")
  install(DIRECTORY launch/
    DESTINATION share/${PROJECT_NAME}/launch)
endif()

ament_package()
