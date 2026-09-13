#include "gurad_handle_ros2/de43_xs.hpp"
#include <memory>

int main(int argc, char **argv) {
    rclcpp::init(argc, argv);
    
    try {
        auto node = std::make_shared<DE43Xprocess>();
        rclcpp::spin(node);
    } catch (const std::exception& e) {
        std::cerr << "Exception in main: " << e.what() << std::endl;
        return 1;
    }
    
    rclcpp::shutdown();
    return 0;
}
