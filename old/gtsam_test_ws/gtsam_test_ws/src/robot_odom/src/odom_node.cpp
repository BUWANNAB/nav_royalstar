#include <rclcpp/rclcpp.hpp>
#include <geometry_msgs/msg/twist.hpp>
#include <nav_msgs/msg/odometry.hpp>
#include <tf2_ros/transform_broadcaster.h>
#include <geometry_msgs/msg/transform_stamped.hpp>
#include <tf2/LinearMath/Quaternion.h>

using std::placeholders::_1;

class OdomNode : public rclcpp::Node
{
public:
  OdomNode()
  : Node("robot_odom_node")
  {
    // 1. 声明参数（机器人轮间距和轮子半径，这里使用默认值，可通过启动文件或命令行覆盖）
    this->declare_parameter<double>("wheel_separation", 0.5);
    this->declare_parameter<double>("wheel_radius", 0.1);

    // 获取参数值
    wheel_separation_ = this->get_parameter("wheel_separation").as_double();
    wheel_radius_ = this->get_parameter("wheel_radius").as_double();

    RCLCPP_INFO(this->get_logger(), "Wheel separation: %.2f m, Wheel radius: %.2f m",
                wheel_separation_, wheel_radius_);

    // 2. 创建发布者和订阅者
    // 发布里程计消息
    odom_pub_ = this->create_publisher<nav_msgs::msg::Odometry>("calculate_odom", 10);
    // 订阅速度命令
    cmd_vel_sub_ = this->create_subscription<geometry_msgs::msg::Twist>(
      "cmd_vel", 10, std::bind(&OdomNode::cmd_vel_callback, this, _1));

    // 3. 创建TF广播器
    tf_broadcaster_ = std::make_unique<tf2_ros::TransformBroadcaster>(*this);

    // 4. 初始化变量
    x_ = 0.0;
    y_ = 0.0;
    th_ = 0.0;

    vx_ = 0.0;
    vy_ = 0.0;
    vth_ = 0.0;

    // 5. 创建定时器，以固定频率发布里程计和TF
    // 这里使用50Hz的频率进行积分和发布
    timer_ = this->create_wall_timer(
      std::chrono::milliseconds(20), // 50Hz: 1000ms/50 = 20ms
      std::bind(&OdomNode::update_odom, this));

    last_time_ = this->now();
    RCLCPP_INFO(this->get_logger(), "Robot Odom Node Started and Initialized.");
  }

private:
  void cmd_vel_callback(const geometry_msgs::msg::Twist::SharedPtr msg)
  {
    // 当收到新的cmd_vel消息时，更新当前速度
    vx_ = msg->linear.x;
    vy_ = msg->linear.y; // 对于差分驱动机器人，通常为0
    vth_ = msg->angular.z;

    // RCLCPP_DEBUG(this->get_logger(), "Received cmd_vel: vx=%.2f, vy=%.2f, vth=%.2f", vx_, vy_, vth_);
  }

  void update_odom()
  {
    auto current_time = this->now();
    // 计算自上次更新以来的时间差（delta_t）
    double dt = (current_time - last_time_).seconds();

    // 基于当前速度和dt，计算位置和角度的增量（数值积分）
    double delta_x = (vx_ * cos(th_) - vy_ * sin(th_)) * dt;
    double delta_y = (vx_ * sin(th_) + vy_ * cos(th_)) * dt;
    double delta_th = vth_ * dt;

    // 更新机器人的位姿（位置和朝向）
    x_ += delta_x;
    y_ += delta_y;
    th_ += delta_th;

    // 创建并填充里程计消息
    auto odom_msg = std::make_unique<nav_msgs::msg::Odometry>();
    odom_msg->header.stamp = current_time;
    odom_msg->header.frame_id = "odom";
    odom_msg->child_frame_id = "base_footprint";

    // 设置位置
    odom_msg->pose.pose.position.x = x_;
    odom_msg->pose.pose.position.y = y_;
    odom_msg->pose.pose.position.z = 0.0;

    // 使用tf2库从偏航角（th_）创建四元数
    tf2::Quaternion quat;
    quat.setRPY(0, 0, th_);
    odom_msg->pose.pose.orientation.x = quat.x();
    odom_msg->pose.pose.orientation.y = quat.y();
    odom_msg->pose.pose.orientation.z = quat.z();
    odom_msg->pose.pose.orientation.w = quat.w();

    // 设置速度（在子坐标系base_footprint下）
    odom_msg->twist.twist.linear.x = vx_;
    odom_msg->twist.twist.linear.y = vy_;
    odom_msg->twist.twist.angular.z = vth_;

    // 发布里程计消息
    odom_pub_->publish(std::move(odom_msg));

    // 创建并发布TF变换：从odom坐标系到base_footprint坐标系
    geometry_msgs::msg::TransformStamped transform_stamped;
    transform_stamped.header.stamp = current_time;
    transform_stamped.header.frame_id = "odom";
    transform_stamped.child_frame_id = "base_footprint";

    transform_stamped.transform.translation.x = x_;
    transform_stamped.transform.translation.y = y_;
    transform_stamped.transform.translation.z = 0.0;

    transform_stamped.transform.rotation.x = quat.x();
    transform_stamped.transform.rotation.y = quat.y();
    transform_stamped.transform.rotation.z = quat.z();
    transform_stamped.transform.rotation.w = quat.w();

    // 发送变换
    tf_broadcaster_->sendTransform(transform_stamped);

    // 更新上一次的时间戳
    last_time_ = current_time;
  }

  // 参数
  double wheel_separation_;
  double wheel_radius_;

  // 机器人的状态（位姿和速度）
  double x_, y_, th_;       // 位置 (x, y) 和朝向 (theta, 偏航角)
  double vx_, vy_, vth_;     // 速度在x, y方向上的线速度和角速度

  // ROS 2 对象
  rclcpp::Publisher<nav_msgs::msg::Odometry>::SharedPtr odom_pub_;
  rclcpp::Subscription<geometry_msgs::msg::Twist>::SharedPtr cmd_vel_sub_;
  std::unique_ptr<tf2_ros::TransformBroadcaster> tf_broadcaster_;
  rclcpp::TimerBase::SharedPtr timer_;

  rclcpp::Time last_time_;
};

int main(int argc, char * argv[])
{
  rclcpp::init(argc, argv);
  auto node = std::make_shared<OdomNode>();
  rclcpp::spin(node);
  rclcpp::shutdown();
  return 0;
}
