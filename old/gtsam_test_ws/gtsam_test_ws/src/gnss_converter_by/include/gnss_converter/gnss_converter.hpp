#ifndef GNSS_CONVERTER__GNSS_CONVERTER_HPP_
#define GNSS_CONVERTER__GNSS_CONVERTER_HPP_

#include <memory>
#include <cmath>

#include "rclcpp/rclcpp.hpp"
#include "tf2_ros/transform_broadcaster.h"
#include "geometry_msgs/msg/pose_stamped.hpp"
#include "customize_interfaces/msg/gnss.hpp"
#include "novatel_oem7_msgs/msg/heading2.hpp"
#include "novatel_oem7_msgs/msg/bestgnsspos.hpp"
#include "nav_msgs/msg/odometry.hpp"
#include "std_msgs/msg/bool.hpp"
#include "sensor_msgs/msg/nav_sat_fix.hpp"
#include "sensor_msgs/msg/nav_sat_status.hpp"
#include "std_msgs/msg/float64_multi_array.hpp" 
#include "std_msgs/msg/u_int32.hpp"
#include "std_msgs/msg/float32.hpp" 

namespace gnss_converter
{

class GNSSConverter : public rclcpp::Node
{
public:
  explicit GNSSConverter(const rclcpp::NodeOptions & options = rclcpp::NodeOptions());

private:
  // WGS84椭球参数
  const double a_ = 6378137.0;         // 长半轴（米）
  const double f_ = 1.0 / 298.257223563; // 扁率
  const double b_ = a_ * (1.0 - f_);   // 短半轴（米）
  const double e2_ = 2 * f_ - f_ * f_; // 第一偏心率平方
  
  double heading_angle_;
  
  sensor_msgs::msg::NavSatFix fix_raw_;

  // 参考点坐标
  double ref_lat_;
  double ref_lon_;
  double ref_height_;
  
  //GNSS->导航中心
  double gnss_offset_x_;
  double gnss_offset_y_;
  
  double offset_east_;
  double offset_north_;

  // ROS2相关成员
  rclcpp::Subscription<customize_interfaces::msg::GNSS>::SharedPtr gnss_sub_;
  rclcpp::Subscription<sensor_msgs::msg::NavSatFix>::SharedPtr gnss_fix_sub_;
  
  rclcpp::Subscription<novatel_oem7_msgs::msg::BESTGNSSPOS>::SharedPtr gnss_by_sub_;
  rclcpp::Subscription<novatel_oem7_msgs::msg::HEADING2>::SharedPtr gnss_byhead_sub_;
  
  rclcpp::Publisher<geometry_msgs::msg::PoseStamped>::SharedPtr pose_pub_;
  rclcpp::Publisher<nav_msgs::msg::Odometry>::SharedPtr odom_pub_;
  rclcpp::Publisher<std_msgs::msg::Bool>::SharedPtr gnss_pos_type_pub_;
  rclcpp::Publisher<std_msgs::msg::Float64MultiArray>::SharedPtr gnss_pos_type_raw_pub_;
  rclcpp::Publisher<std_msgs::msg::UInt32>::SharedPtr by_gnss_pos_type_raw_pub_;
  rclcpp::Publisher<std_msgs::msg::Float32>::SharedPtr by_gnss_pos_stdev_pub_;
  rclcpp::Publisher<std_msgs::msg::Float32>::SharedPtr by_gnss_yaw_stdev_pub_;
    
  std::unique_ptr<tf2_ros::TransformBroadcaster> tf_broadcaster_;

  // 回调函数
  void gnssQFCallback(const customize_interfaces::msg::GNSS::SharedPtr msg);
  void gnssQFFixCallback(const sensor_msgs::msg::NavSatFix::SharedPtr msg);
  void gnssBYCallback(const novatel_oem7_msgs::msg::BESTGNSSPOS::SharedPtr msg);
  void gnssBYHEADCallback(const novatel_oem7_msgs::msg::HEADING2::SharedPtr msg);
  // 工具函数
  double deg2rad(double deg);
  double calcN(double lat_rad);
  void geodeticToCartesian(double lat, double lon, double height, double &x, double &y, double &z);
  void ecefToEnu(double ref_lat, double ref_lon, double ref_height,
                double target_x, double target_y, double target_z,
                double &east, double &north, double &up);
};

}  // namespace gnss_converter

#endif  // GNSS_CONVERTER__GNSS_CONVERTER_HPP_
