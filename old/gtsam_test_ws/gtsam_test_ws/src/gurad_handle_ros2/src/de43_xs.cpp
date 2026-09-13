#include "gurad_handle_ros2/de43_xs.hpp"
#include <iostream>
#include <iomanip>
#include <sstream>

DE43Xprocess::DE43Xprocess() 
    : Node("DE43Xprocess"), 
      serial_port_(io_service_),
      running_(true) {
    
    // 创建发布者和订阅者
    de43_publisher_ = this->create_publisher<customize_interfaces::msg::DE43X>("de43x_data", 10);
    de43_array_publisher_ = this->create_publisher<std_msgs::msg::UInt8MultiArray>("/de43_array_data", 10);
    
    subscription_ = this->create_subscription<std_msgs::msg::String>(
        "ros_to_com", 10,
        std::bind(&DE43Xprocess::send_to_com, this, std::placeholders::_1));
        
    subDE43set_ = this->create_subscription<std_msgs::msg::UInt8>(
        "de43x_set", 10,
        std::bind(&DE43Xprocess::de43x_set, this, std::placeholders::_1));
        
    timer_ = this->create_wall_timer(
        std::chrono::milliseconds(100),  // 定时周期
        std::bind(&DE43Xprocess::timer_callback, this)  // 回调函数
    );

    // 配置串口
    try {
        serial_port_.open("/dev/ttyS3");
        serial_port_.set_option(boost::asio::serial_port_base::baud_rate(115200));
        serial_port_.set_option(boost::asio::serial_port_base::character_size(8));
        serial_port_.set_option(boost::asio::serial_port_base::parity(boost::asio::serial_port_base::parity::none));
        serial_port_.set_option(boost::asio::serial_port_base::stop_bits(boost::asio::serial_port_base::stop_bits::one));
        
        RCLCPP_INFO(this->get_logger(), "Serial port /dev/ttyUSB0 opened successfully");
    } catch (const std::exception &e) {
        RCLCPP_ERROR(this->get_logger(), "Failed to open serial port: %s", e.what());
        throw;
    }

    // 启动IO服务线程
    io_thread_ = std::thread([this]() {
        while (running_) {
            try {
                io_service_.run();
                break;
            } catch (const std::exception& e) {
                RCLCPP_ERROR(this->get_logger(), "IO service error: %s", e.what());
                std::this_thread::sleep_for(std::chrono::milliseconds(100));
            }
        }
    });

    // 启动异步读取
    //start_async_read();
}

DE43Xprocess::~DE43Xprocess() {
    running_ = false;
    io_service_.stop();
    if (io_thread_.joinable()) {
        io_thread_.join();
    }
    if (serial_port_.is_open()) {
        serial_port_.close();
    }
}

unsigned char setDE43X[] = {0x53, 0x43, 0x68, 0x69, 0x6F, 0x00, 0xA8, 0x3B};
// 定时器回调函数
void DE43Xprocess::timer_callback() {
    senddata(setDE43X, sizeof(setDE43X));
    //start_async_read();
    start_sync_read() ;
    RCLCPP_INFO(this->get_logger(), "定时发送数据");
}

// 启动异步读取
// 启动异步读取
void DE43Xprocess::start_sync_read() {
    try {
        //while (true) 
        {  // 持续读取循环
            // 使用streambuf来管理接收缓冲区
            std::cout << "start_rev: " << std::endl;
            buffer_.resize(100);

            // 同步读取数据
            size_t bytes_read = boost::asio::read(
                serial_port_,
                boost::asio::buffer(buffer_),
                boost::asio::transfer_at_least(13)  // 至少读取13字节
            );

            std::cout << "readdata_len: " << bytes_read << std::endl;
            if (bytes_read > 0) {
                // 处理读取的字节数
                std::cout << "readdata_len: " << bytes_read << std::endl; // 打印接收到的数据
                for (size_t i = 0; i < bytes_read; i++) {
                    std::cout << "0x" << std::hex << (int)buffer_[i] << " "; // 打印接收到的数据
                    data_buffer[i] = buffer_[i];
                }
                std::cout << std::endl;
                
                process_data(bytes_read);
            }
        }
    } catch (const boost::system::system_error& error) {
        if (error.code() == boost::asio::error::operation_aborted) {
            // 操作被取消是正常情况，不记录为错误
            RCLCPP_DEBUG(this->get_logger(), "Read operation was cancelled");
        } else {
            // 处理其他错误
            RCLCPP_ERROR(this->get_logger(), "Serial read error: %s", error.what());
            
            // 尝试重新连接
            if (error.code() == boost::asio::error::eof || 
                error.code() == boost::asio::error::connection_reset) {
                // try_reconnect();
            }
        }
    } catch (const std::exception& e) {
        RCLCPP_ERROR(this->get_logger(), "Exception: %s", e.what());
    }
}

// 处理接收到的数据
void DE43Xprocess::process_data(size_t bytes_read) {
    uint16touint8 data_conversion;
    //data_conversion.Data8[0] = data_buffer[12]; 
    //data_conversion.Data8[1] = data_buffer[11];
    uint16_t checksum = calculateModbusRTUCrc(data_buffer, bytes_read-2);
    data_conversion.Data16 = checksum;
    std::cout << "check: " << checksum << std::endl;
    //std::cout << "data_conversion: " <<std::hex<< (int)data_conversion.Data8[0] << "   "<< (int)data_conversion.Data8[1]  << std::endl;
    if(data_conversion.Data8[0] == data_buffer[11] && data_conversion.Data8[1] == data_buffer[12])
    {
        std::cout << "2data_conversion: " <<std::hex<< (int)data_conversion.Data8[0] << "   "<< (int)data_conversion.Data8[1]  << std::endl;
        //auto doubles = extract_doubles(data);
        customize_interfaces::msg::DE43X de43Data;
        de43Data.ch = data_buffer[5];
        de43Data.area1 = data_buffer[6];
        de43Data.area2 = data_buffer[7];
        de43Data.area3 = data_buffer[8];
        de43Data.alarm = data_buffer[10];
        RCLCPP_INFO(this->get_logger(), "Publishing to /de43x_data");
        de43_publisher_->publish(de43Data);
        
        auto msg = std_msgs::msg::UInt8MultiArray();
        msg.data.push_back(data_buffer[5]);
        msg.data.push_back(data_buffer[6]);
        msg.data.push_back(data_buffer[7]);
        msg.data.push_back(data_buffer[8]);
        msg.data.push_back(data_buffer[10]);
        RCLCPP_INFO(this->get_logger(), "Publishing to /de43x_data_erry");
        de43_array_publisher_->publish(msg);
        
    }
    
}

// 将消息发送到 USB
void DE43Xprocess::send_to_com(const std_msgs::msg::String::SharedPtr msg) {
    RCLCPP_INFO(this->get_logger(), "Sent to USB: %s", msg->data.c_str()); // 日志信息
    if (serial_port_.is_open()) {
        boost::asio::write(serial_port_, boost::asio::buffer(msg->data)); // 发送数据到串口
    }
}


void DE43Xprocess::de43x_set(const std_msgs::msg::UInt8::SharedPtr msg) {
    RCLCPP_INFO(this->get_logger(), "de43x_set: %d", msg->data); // 日志信息
    setDE43X[5] = msg->data;
    uint16_t checksum = calculateModbusRTUCrc(setDE43X,6);
    uint16touint8 data_conversion;
    data_conversion.Data16 = checksum;
    setDE43X[6] = data_conversion.Data8[0];
    setDE43X[7] = data_conversion.Data8[1];
    
}

// 将消息发送到 USB
void DE43Xprocess::senddata(const unsigned char* data, size_t length) {
    // 确保数据有效
    if (data == nullptr || length == 0) {
        RCLCPP_ERROR(this->get_logger(), "Invalid data to send");
        return;
    }

    // 打印日志信息（十六进制格式）
    std::stringstream hex_ss;
    hex_ss << "Sent to USB (hex):";
    for (size_t i = 0; i < length; ++i) {
        hex_ss << " 0x" << std::hex << std::setw(2) << std::setfill('0') 
               << static_cast<int>(data[i]);
    }
    RCLCPP_INFO(this->get_logger(), "%s", hex_ss.str().c_str());

    // 检查串口是否打开
    if (!serial_port_.is_open()) {
        RCLCPP_ERROR(this->get_logger(), "Serial port is not open");
        return;
    }

    // 使用shared_ptr确保数据在异步操作期间保持有效
    auto buffer = std::make_shared<std::vector<unsigned char>>(data, data + length);

    // 异步写入数据
    boost::asio::async_write(
        serial_port_,
        boost::asio::buffer(*buffer),
        [this, buffer](const boost::system::error_code& ec, std::size_t bytes_sent) {
            if (!ec) {
                RCLCPP_DEBUG(this->get_logger(), "Successfully sent %zu bytes", bytes_sent);
            } else {
                RCLCPP_ERROR(this->get_logger(), "Error sending data: %s", ec.message().c_str());
                
                // 处理特定错误情况
                if (ec == boost::asio::error::eof) {
                    RCLCPP_WARN(this->get_logger(), "Receiver socket closed");
                }
            }
        }
    );
}

//BCC校验
bool DE43Xprocess::validate_bcc(const std::string& nmea) {
  size_t asterisk_pos = nmea.find('*');
  if (asterisk_pos == std::string::npos || asterisk_pos + 3 > nmea.size()) 
      return false;

  uint8_t checksum = 0;
  for (size_t i = 1; i < asterisk_pos; ++i) {  // 跳过起始'$'
      checksum ^= nmea[i];
  }

  uint16_t received_checksum;
  std::istringstream iss(nmea.substr(asterisk_pos + 1, 2));
  iss >> std::hex >> received_checksum;
  
  uint16_t checksum2 = (uint16_t)checksum;

  std::cout << "checksum: " << checksum2<< " received_checksum : " <<received_checksum<< std::endl;
  
  return checksum == checksum2;
}

/*modbus crc校验*/
uint16_t DE43Xprocess::calculateModbusRTUCrc(uint8_t* data, uint32_t length) 
{
  uint16_t crc = 0xFFFF; // 初始化
  for (uint32_t i = 0; i < length; i++) {
    crc ^= data[i]; // 数据字节异或
    for (uint8_t j = 0; j < 8; j++) {
      if (crc & 0x0001) {
        crc >>= 1;
        crc ^= 0xA001; // 生成多项式为0xA001
      } else {
        crc >>= 1;
      }
    }
  }
  return crc;
}





