cmake_minimum_required(VERSION 3.8)
project(electric_bin)

# 编译警告配置
if(CMAKE_COMPILER_IS_GNUCXX OR CMAKE_CXX_COMPILER_ID MATCHES "Clang")
  add_compile_options(-Wall -Wextra -Wpedantic)
endif()

# 查找ROS2依赖
find_package(ament_cmake REQUIRED)
find_package(rclcpp REQUIRED)
find_package(std_msgs REQUIRED)

# 查找系统Modbus库（关键：链接硬件通讯库）
find_library(MODBUS_LIB modbus)
if(NOT MODBUS_LIB)
  message(FATAL_ERROR "libmodbus not found! Please install it with: sudo apt install libmodbus-dev")
endif()

# 编译C++节点：将electric_bin_node.cpp编译为可执行文件
add_executable(electric_bin_node src/electric_bin_node.cpp)
# 指定头文件目录
target_include_directories(electric_bin_node PUBLIC
  $<BUILD_INTERFACE:${CMAKE_CURRENT_SOURCE_DIR}/include>
  $<INSTALL_INTERFACE:include>)
# 适配ROS2 Humble的C++17标准
target_compile_features(electric_bin_node PUBLIC c_std_99 cxx_std_17)
# 链接ROS2依赖库
ament_target_dependencies(
  electric_bin_node
  "rclcpp"
  "std_msgs"
)
# 链接Modbus硬件通讯库（核心）
target_link_libraries(electric_bin_node ${MODBUS_LIB})

# 安装可执行文件到ROS2系统目录（保证ros2 run能调用）
install(TARGETS electric_bin_node
  DESTINATION lib/${PROJECT_NAME})

# 测试配置（默认保留，不影响运行）
if(BUILD_TESTING)
  find_package(ament_lint_auto REQUIRED)
  ament_lint_auto_find_test_dependencies()
endif()
# 安装launch和config文件
install(DIRECTORY
  launch
  config
  DESTINATION share/${PROJECT_NAME}
)
# 声明ROS2包
ament_package()

