#ifndef GNSS_SERIAL_NODE_HPP
#define GNSS_SERIAL_NODE_HPP

#include "sensor_msgs/msg/nav_sat_fix.hpp"
#include "sensor_msgs/msg/nav_sat_status.hpp"
#include "std_msgs/msg/header.hpp"
#include "rclcpp/rclcpp.hpp"
#include "std_msgs/msg/string.hpp"
#include <boost/asio.hpp>
#include <boost/bind/bind.hpp>
#include <sys/ioctl.h>
#include <unistd.h>
#include <queue>
#include <mutex>
#include <atomic>
#include <string>
#include <vector>
#include "customize_interfaces/msg/gnss.hpp"
#include "customize_interfaces/msg/de43_x.hpp"
#include <std_msgs/msg/u_int8_multi_array.hpp>
#include <std_msgs/msg/u_int8.hpp>

typedef union
{
    uint16_t Data16;
    uint8_t Data8[2];
} uint16touint8;

class DE43Xprocess : public rclcpp::Node {
public:
    DE43Xprocess();
    ~DE43Xprocess();

private:
    // 串口相关成员
    boost::asio::io_service io_service_;
    boost::asio::serial_port serial_port_;
    std::vector<uint8_t> buffer_;
    std::atomic<bool> running_;
    std::thread io_thread_;
    uint8_t data_buffer[20];
    // ROS2相关成员
    rclcpp::Publisher<customize_interfaces::msg::DE43X>::SharedPtr de43_publisher_;
    rclcpp::Publisher<std_msgs::msg::UInt8MultiArray>::SharedPtr de43_array_publisher_;
    
    rclcpp::Subscription<std_msgs::msg::String>::SharedPtr subscription_;
    rclcpp::Subscription<std_msgs::msg::UInt8>::SharedPtr subDE43set_;
    rclcpp::TimerBase::SharedPtr timer_;
    
    //std::vector<char> buffer; // 用于存放读取数据的缓冲区
    std::queue<std::string> data_queue_;
    bool star_rev = false;
    std::string datac_append;
    // 方法
    void start_async_read();
    void start_sync_read();
    void process_data(size_t bytes_read);
    void send_to_com(const std_msgs::msg::String::SharedPtr msg);
    void de43x_set(const std_msgs::msg::UInt8::SharedPtr msg) ;
    
    void senddata(const unsigned char* data, size_t length);
    
    void timer_callback();
    
    bool validate_bcc(const std::string& nmea);
    uint16_t calculateModbusRTUCrc(uint8_t* data, uint32_t length) ;
 
};

#endif // COM_SERIAL_NODE_HPP
