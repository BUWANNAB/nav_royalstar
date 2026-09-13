#include "gnss_converter/gnss_converter.hpp"
#include "rclcpp/rclcpp.hpp"

int main(int argc, char** argv) {
  rclcpp::init(argc, argv);
  auto node = std::make_shared<gnss_converter::GNSSConverter>();
  rclcpp::spin(node);
  rclcpp::shutdown();
  return 0;
}
