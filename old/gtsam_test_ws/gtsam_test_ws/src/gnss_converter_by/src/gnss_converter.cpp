#include "gnss_converter/gnss_converter.hpp"

namespace gnss_converter
{

GNSSConverter::GNSSConverter(const rclcpp::NodeOptions & options)
: Node("gnss_converter", options)
{
  // 声明参数并设置默认值（北京坐标）
  this->declare_parameter("ref_latitude", 39.9042);
  this->declare_parameter("ref_longitude", 116.4074);
  this->declare_parameter("ref_height", 50.0);
  this->declare_parameter("gnss_offset_x", 0.0);
  this->declare_parameter("gnss_offset_y", 0.0);

  // 获取参数
  ref_lat_ = this->get_parameter("ref_latitude").as_double();
  ref_lon_ = this->get_parameter("ref_longitude").as_double();
  ref_height_ = this->get_parameter("ref_height").as_double();
  
  gnss_offset_x_ = this->get_parameter("gnss_offset_x").as_double();
  gnss_offset_y_ = this->get_parameter("gnss_offset_y").as_double();

  // 创建订阅者
  gnss_sub_ = this->create_subscription<customize_interfaces::msg::GNSS>(
    "/gnss_data", 10, std::bind(&GNSSConverter::gnssQFCallback, this, std::placeholders::_1));
  gnss_fix_sub_ = this->create_subscription<sensor_msgs::msg::NavSatFix>(
    "gnss_fix", 10, std::bind(&GNSSConverter::gnssQFFixCallback, this, std::placeholders::_1));

  gnss_by_sub_ = this->create_subscription<novatel_oem7_msgs::msg::BESTGNSSPOS>(
    "/bynav/bestgnsspos", 10, std::bind(&GNSSConverter::gnssBYCallback, this, std::placeholders::_1));
  gnss_byhead_sub_ = this->create_subscription<novatel_oem7_msgs::msg::HEADING2>(
    "/bynav/heading2", 10, std::bind(&GNSSConverter::gnssBYHEADCallback, this, std::placeholders::_1));
  // 创建发布者
  pose_pub_ = this->create_publisher<geometry_msgs::msg::PoseStamped>("gnss_pose", 10);
  
  // 新增Odometry发布者
  odom_pub_ = this->create_publisher<nav_msgs::msg::Odometry>("odometry_gps", 10);
  
  //新增发布定位类型 50正常
  gnss_pos_type_pub_ = this->create_publisher<std_msgs::msg::Bool>("gnss_pos_type", 10);
  gnss_pos_type_raw_pub_ = this->create_publisher<std_msgs::msg::Float64MultiArray>("gnss_pos_type_raw", 10);
  by_gnss_pos_type_raw_pub_ = this->create_publisher<std_msgs::msg::UInt32>("by_gnss_pos_type_raw", 10);
  by_gnss_pos_stdev_pub_ = this->create_publisher<std_msgs::msg::Float32>("by_gnss_pos_stdev", 10);
  by_gnss_yaw_stdev_pub_ = this->create_publisher<std_msgs::msg::Float32>("by_gnss_yaw_stdev", 10);

  // 创建TF广播器
  tf_broadcaster_ = std::make_unique<tf2_ros::TransformBroadcaster>(*this);

  RCLCPP_INFO(this->get_logger(), "GNSS Converter initialized with reference point: lat %f, lon %f, height %f, offset_x %f, offset_y %f",
              ref_lat_, ref_lon_, ref_height_, gnss_offset_x_, gnss_offset_y_);
}

//全方传感器数据解析
void GNSSConverter::gnssQFCallback(const customize_interfaces::msg::GNSS::SharedPtr msg)
{
  // 检查GNSS数据状态
  if (msg->location_status == 0 || msg->heading_status == 0) {
    RCLCPP_WARN(this->get_logger(), "GNSS data is invalid: location_status=%f, heading_status=%f",
                msg->location_status, msg->heading_status);
    //return;
  }
  
  bool gnss_pose_type = false;
  if(msg->location_status == 4.0 && msg->heading_status == 4.0)
  {
    gnss_pose_type = true;
  }
  // 发布当前模式
  auto type_msg = std_msgs::msg::Bool();
  type_msg.data = gnss_pose_type;
  gnss_pos_type_pub_->publish(type_msg);
  
  // 发布当前模式
  auto type_raw_msg = std_msgs::msg::Float64MultiArray();
  type_raw_msg.data = {msg->location_status, msg->heading_status};
  gnss_pos_type_raw_pub_->publish(type_raw_msg);
  
  //RCLCPP_DEBUG(this->get_logger(), "Published transform and pose: east, north, up, heading");

  // 计算目标点ECEF坐标
  double target_x, target_y, target_z;
  geodeticToCartesian(msg->latitude, msg->longitude, msg->altitude, target_x, target_y, target_z);

  // 转换为ENU坐标
  double east, north, up;
  ecefToEnu(ref_lat_, ref_lon_, ref_height_, target_x, target_y, target_z, east, north, up);

  // 创建并发布PoseStamped消息
  auto pose_msg = std::make_shared<geometry_msgs::msg::PoseStamped>();
  pose_msg->header.stamp = fix_raw_.header.stamp;
  pose_msg->header.frame_id = "map";
  //pose_msg->pose.position.x = north;//east;
  //pose_msg->pose.position.y = 0-east;//north;
  //pose_msg->pose.position.z = up;
  
  heading_angle_ = msg->heading_angle; //360-(msg->heading_angle-90);
  
  // 设置航向角（从北向东为正方向）
  double heading_rad = deg2rad(heading_angle_);
  
  offset_east_  = east  - ( ( gnss_offset_x_ * cos(heading_rad) ) - ( gnss_offset_y_ * sin(heading_rad) ) );
  offset_north_ = north - ( ( gnss_offset_x_ * sin(heading_rad) ) + ( gnss_offset_y_ * cos(heading_rad) ) );
  //curpose2.yaw   := GVL_COMMON.PI * (360 - GVL_COM.curLatLonAltHeadSta.Heading)/ 180.0; //航向角转换，满足笛卡尔坐标系
  
  pose_msg->pose.position.x = offset_east_;
  pose_msg->pose.position.y = offset_north_;
  pose_msg->pose.position.z = up;
  
  pose_msg->pose.orientation.z = sin(heading_rad / 2);
  pose_msg->pose.orientation.w = cos(heading_rad / 2);

  pose_pub_->publish(*pose_msg);
  
  
  // 创建并发布Odometry消息
  auto odom_msg = std::make_shared<nav_msgs::msg::Odometry>();
  odom_msg->header.stamp = fix_raw_.header.stamp;//msg->header.stamp;
  odom_msg->header.frame_id = "odom";
  odom_msg->child_frame_id = "gnss_link";
  
  // 设置位置
  odom_msg->pose.pose.position.x = offset_east_ ;//east;
  odom_msg->pose.pose.position.y = offset_north_;//north;
  odom_msg->pose.pose.position.z = up;
  
  // 设置方向（假设航向角为0，实际应用中可能需要从IMU获取）
  odom_msg->pose.pose.orientation.z = sin(heading_rad / 2);
  odom_msg->pose.pose.orientation.w = cos(heading_rad / 2);
  
  // 设置协方差（示例值）
  odom_msg->pose.covariance[0] = 0.1;  // x方差
  odom_msg->pose.covariance[7] = 0.1;  // y方差
  odom_msg->pose.covariance[14] = 0.1; // z方差
  
  odom_pub_->publish(*odom_msg);


  // 发布TF变换 map -> gnss_link
  geometry_msgs::msg::TransformStamped transform;
  transform.header.stamp = this->now();
  transform.header.frame_id = "odom";
  transform.child_frame_id = "gnss_link";
  transform.transform.translation.x = north;//east;
  transform.transform.translation.y = 0-east;//north;
  transform.transform.translation.z = up;
  transform.transform.rotation.z = sin(heading_rad / 2);
  transform.transform.rotation.w = cos(heading_rad / 2);

  tf_broadcaster_->sendTransform(transform);

  RCLCPP_INFO(this->get_logger(), "Published transform and pose: eastx=%f, northy=%f, up=%f, heading=%f",
               offset_east_, offset_north_, up, msg->heading_angle);
}

//借用时间戳，只使用时间戳
void GNSSConverter::gnssQFFixCallback(const sensor_msgs::msg::NavSatFix::SharedPtr msg)
{
  fix_raw_ = *msg;
}

void GNSSConverter::gnssBYCallback(const novatel_oem7_msgs::msg::BESTGNSSPOS::SharedPtr msg)
{
  // 检查GNSS数据状态
 /*
  if (msg->location_status == 0 || msg->heading_status == 0) {
    RCLCPP_WARN(this->get_logger(), "GNSS data is invalid: location_status=%f, heading_status=%f",
                msg->location_status, msg->heading_status);
    //return;
  }
*/

  bool gnss_pose_type = false;
  if(msg->pos_type.type == 50) 
  {
    gnss_pose_type = true;
  }
  // 发布当前模式                  
  auto type_msg = std_msgs::msg::Bool();
  type_msg.data = gnss_pose_type;
  gnss_pos_type_pub_->publish(type_msg);
  
  // 发布当前模式
  auto type_raw_msg = std_msgs::msg::UInt32();
  type_raw_msg.data = msg->pos_type.type;
  by_gnss_pos_type_raw_pub_->publish(type_raw_msg);

  // 发布最大的标准差
  auto stdev_msg = std_msgs::msg::Float32();
  stdev_msg.data = std::max({msg->lat_stdev, msg->lon_stdev, msg->hgt_stdev});  //msg->pos_type.type;
  by_gnss_pos_stdev_pub_->publish(stdev_msg);
        
  // 计算目标点ECEF坐标
  double target_x, target_y, target_z;
  geodeticToCartesian(msg->lat, msg->lon, msg->hgt, target_x, target_y, target_z);

  // 转换为ENU坐标
  double east, north, up;
  ecefToEnu(ref_lat_, ref_lon_, ref_height_, target_x, target_y, target_z, east, north, up);

  // 创建并发布PoseStamped消息
  auto pose_msg = std::make_shared<geometry_msgs::msg::PoseStamped>();
  pose_msg->header.stamp = msg->header.stamp;
  pose_msg->header.frame_id = "map";
  //pose_msg->pose.position.x = east;
  //pose_msg->pose.position.y = north;
  //pose_msg->pose.position.z = up;
  
  heading_angle_ = 360-(heading_angle_-90);
  
  // 设置航向角（从北向东为正方向）
  double heading_rad = deg2rad(heading_angle_);
  
  offset_east_  = east  - ( ( gnss_offset_x_ * cos(heading_rad) ) - ( gnss_offset_y_ * sin(heading_rad) ) );
  offset_north_ = north - ( ( gnss_offset_x_ * sin(heading_rad) ) + ( gnss_offset_y_ * cos(heading_rad) ) );
  //curpose2.yaw   := GVL_COMMON.PI * (360 - GVL_COM.curLatLonAltHeadSta.Heading)/ 180.0; //航向角转换，满足笛卡尔坐标系
  
  pose_msg->pose.position.x = offset_east_;
  pose_msg->pose.position.y = offset_north_;
  pose_msg->pose.position.z = up;
  
  pose_msg->pose.orientation.z = sin(heading_rad / 2);
  pose_msg->pose.orientation.w = cos(heading_rad / 2);

  pose_pub_->publish(*pose_msg);
  
  
  // 创建并发布Odometry消息
  auto odom_msg = std::make_shared<nav_msgs::msg::Odometry>();
  odom_msg->header.stamp = msg->header.stamp;
  odom_msg->header.frame_id = "map";
  odom_msg->child_frame_id = "gnss_link";
  
  // 设置位置
  odom_msg->pose.pose.position.x = offset_east_ ;//east;
  odom_msg->pose.pose.position.y = offset_north_;//north;
  odom_msg->pose.pose.position.z = up;
  
  // 设置方向（假设航向角为0，实际应用中可能需要从IMU获取）
  odom_msg->pose.pose.orientation.z = sin(heading_rad / 2);
  odom_msg->pose.pose.orientation.w = cos(heading_rad / 2);
  
  // 设置协方差（示例值）
  odom_msg->pose.covariance[0] = 0.1;  // x方差
  odom_msg->pose.covariance[7] = 0.1;  // y方差
  odom_msg->pose.covariance[14] = 0.1; // z方差
  
  odom_pub_->publish(*odom_msg);


  // 发布TF变换 map -> gnss_link
  geometry_msgs::msg::TransformStamped transform;
  transform.header.stamp = msg->header.stamp;
  transform.header.frame_id = "odom";
  transform.child_frame_id = "gnss_link";
  transform.transform.translation.x = east;
  transform.transform.translation.y = north;
  transform.transform.translation.z = up;
  transform.transform.rotation.z = sin(heading_rad / 2);
  transform.transform.rotation.w = cos(heading_rad / 2);

  tf_broadcaster_->sendTransform(transform);

  RCLCPP_INFO(this->get_logger(), "Published transform and pose: east=%f, north=%f, up=%f, heading=%f",
               offset_east_, offset_north_, up, heading_angle_);
}

void GNSSConverter::gnssBYHEADCallback(const novatel_oem7_msgs::msg::HEADING2::SharedPtr msg)
{
  heading_angle_ = msg->heading;
  // 发布最大的标准差
  auto stdev_msg = std_msgs::msg::Float32();
  stdev_msg.data = msg->heading_stdev;  //msg->pos_type.type;
  by_gnss_yaw_stdev_pub_->publish(stdev_msg);
  //geodeticToCartesian(msg->lat, msg->lon, msg->hgt, target_x, target_y, target_z);
}


double GNSSConverter::deg2rad(double deg)
{
  return deg * M_PI / 180.0;
}

double GNSSConverter::calcN(double lat_rad)
{
  return a_ / sqrt(1 - e2_ * pow(sin(lat_rad), 2));
}

void GNSSConverter::geodeticToCartesian(double lat, double lon, double height, 
                                       double &x, double &y, double &z)
{
  double lat_rad = deg2rad(lat);
  double lon_rad = deg2rad(lon);
  double N = calcN(lat_rad);
  
  x = (N + height) * cos(lat_rad) * cos(lon_rad);
  y = (N + height) * cos(lat_rad) * sin(lon_rad);
  z = (N * (1 - e2_) + height) * sin(lat_rad);
}

void GNSSConverter::ecefToEnu(double ref_lat, double ref_lon, double ref_height,
                            double target_x, double target_y, double target_z,
                            double &east, double &north, double &up)
{
  // 参考点ECEF坐标
  double ref_x, ref_y, ref_z;
  geodeticToCartesian(ref_lat, ref_lon, ref_height, ref_x, ref_y, ref_z);
  
  // 计算相对位移
  double dx = target_x - ref_x;
  double dy = target_y - ref_y;
  double dz = target_z - ref_z;
  
  // 转换为弧度
  double lat_rad = deg2rad(ref_lat);
  double lon_rad = deg2rad(ref_lon);
  
  // ENU转换矩阵（方向余弦矩阵）
  east  = -sin(lon_rad) * dx + cos(lon_rad) * dy;
  north = -sin(lat_rad) * cos(lon_rad) * dx - sin(lat_rad) * sin(lon_rad) * dy + cos(lat_rad) * dz;
  up    =  cos(lat_rad) * cos(lon_rad) * dx + cos(lat_rad) * sin(lon_rad) * dy + sin(lat_rad) * dz;
}

}  // namespace gnss_converter

#include "rclcpp_components/register_node_macro.hpp"
RCLCPP_COMPONENTS_REGISTER_NODE(gnss_converter::GNSSConverter)
