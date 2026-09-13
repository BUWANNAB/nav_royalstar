#include <rclcpp/rclcpp.hpp>
#include <nav_msgs/msg/odometry.hpp>
#include <geometry_msgs/msg/pose_with_covariance_stamped.hpp>
#include <geometry_msgs/msg/pose_stamped.hpp>
#include <nav_msgs/msg/path.hpp>

#include <message_filters/subscriber.h>
#include <message_filters/synchronizer.h>
#include <message_filters/sync_policies/approximate_time.h>

#include <gtsam/geometry/Pose3.h>
#include <gtsam/slam/BetweenFactor.h>
#include <gtsam/slam/PriorFactor.h>
#include <gtsam/navigation/GPSFactor.h>
#include <gtsam/nonlinear/ISAM2.h>
#include <gtsam/nonlinear/Values.h>

#include "gtsam_fusion/factor_graph_builder.h"
#include "gtsam_fusion/pose_optimizer.h"
#include "gtsam_fusion/sliding_window_manager.h"  // 新增头文件
#include "std_msgs/msg/float32_multi_array.hpp" 
#include "std_msgs/msg/u_int32.hpp"
#include "std_msgs/msg/float32.hpp" 

#include <tf2/LinearMath/Quaternion.h>
#include <tf2/LinearMath/Matrix3x3.h>
#include <tf2_geometry_msgs/tf2_geometry_msgs.hpp>
#include <tf2/convert.h>
#include "tf2_ros/transform_listener.h"
#include "tf2_ros/buffer.h"
#include "tf2/exceptions.h"
#include "tf2_ros/transform_broadcaster.h"  // 新增：TF广播器头文件
#include "geometry_msgs/msg/transform_stamped.hpp"  // 新增：TF消息头文件

class GtsamFusionNode : public rclcpp::Node
{
public:
  GtsamFusionNode() : Node("gtsam_fusion_node")
  {
    // 声明参数 - 新增滑动窗口参数
    this->declare_parameter("gnss_topic", "odometry_gps");
    this->declare_parameter("lidar_topic", "pcl_pose");
    this->declare_parameter("output_topic", "/fused_pose");
    this->declare_parameter("path_topic", "/fused_path");
    this->declare_parameter("window_size", 10);  // 新增滑动窗口大小参数[4](@ref)
    this->declare_parameter("gnss_sigma_model", "sigma");
    this->declare_parameter("tf_parent_frame", "map");  // 新增：TF父坐标系参数
    this->declare_parameter("tf_child_frame", "fusion_link");  // 新增：TF子坐标系参数
    this->declare_parameter("main_location", "gnss");
    
    this->declare_parameter("gnss_noise_sigma", 2.0);
    this->declare_parameter("lidar_noise_sigma", 0.1);
    this->declare_parameter("odom_noise_sigma", 0.5);
    this->declare_parameter("initpose_enabled", 0.5);
    this->declare_parameter("fitness_score_threshold", 2.0);

    this->declare_parameter("target_frame", "base_link");
    this->declare_parameter("source_frame", "map");
    this->declare_parameter("timeout_seconds", 2.0);

    // 获取参数
    std::string gnss_topic = this->get_parameter("gnss_topic").as_string();
    std::string lidar_topic = this->get_parameter("lidar_topic").as_string();
    std::string output_topic = this->get_parameter("output_topic").as_string();
    std::string path_topic = this->get_parameter("path_topic").as_string();
    gnss_sigma_model = this->get_parameter("gnss_sigma_model").as_string();
    main_location_ = this->get_parameter("main_location").as_string();

    tf_parent_frame_ = this->get_parameter("tf_parent_frame").as_string();  // 获取TF父坐标系
    tf_child_frame_ = this->get_parameter("tf_child_frame").as_string();  // 获取TF子坐标系

    int window_size = this->get_parameter("window_size").as_int();
    target_frame_ = this->get_parameter("target_frame").as_string();
    source_frame_ = this->get_parameter("source_frame").as_string();
    timeout_seconds = this->get_parameter("timeout_seconds").as_double();
    
    double gnss_sigma = this->get_parameter("gnss_noise_sigma").as_double();
    double lidar_sigma = this->get_parameter("lidar_noise_sigma").as_double();
    double odom_sigma = this->get_parameter("odom_noise_sigma").as_double();
    initpose_enabled_ = this->get_parameter("initpose_enabled").as_double();
    fitness_score_threshold_ = this->get_parameter("fitness_score_threshold").as_double();

    // 初始化噪声模型
    gnss_noise_model_ = gtsam::noiseModel::Diagonal::Sigmas(
        (gtsam::Vector(6) << gnss_sigma, gnss_sigma, gnss_sigma, 
                            gnss_sigma, gnss_sigma, gnss_sigma).finished());
    
    lidar_noise_model_ = gtsam::noiseModel::Diagonal::Sigmas(
        (gtsam::Vector(6) << lidar_sigma, lidar_sigma, lidar_sigma, 
                            lidar_sigma, lidar_sigma, lidar_sigma).finished());

    odom_noise_model_ = gtsam::noiseModel::Diagonal::Sigmas(
        (gtsam::Vector(6) << odom_sigma, odom_sigma, odom_sigma, 
                            odom_sigma, odom_sigma, odom_sigma).finished());

    // 初始化滑动窗口管理器[4](@ref)
    sliding_window_manager_ = std::make_unique<SlidingWindowManager>(window_size);

    // 初始化消息过滤器订阅
    gnss_sub_.subscribe(this, gnss_topic);
    lidar_sub_.subscribe(this, lidar_topic);

    // 使用近似时间同步策略
    sync_ = std::make_shared<message_filters::Synchronizer<SyncPolicy>>(
        SyncPolicy(10), gnss_sub_, lidar_sub_);
    sync_->registerCallback(
        std::bind(&GtsamFusionNode::sensorCallback, this, 
                  std::placeholders::_1, std::placeholders::_2));

    // 初始化发布者
    pose_pub_ = this->create_publisher<geometry_msgs::msg::PoseStamped>(output_topic, 10);
    path_pub_ = this->create_publisher<nav_msgs::msg::Path>(path_topic, 10);
    initialpose_pub_ = this->create_publisher<geometry_msgs::msg::PoseWithCovarianceStamped>("initialpose", 10);
    // 新增：滑动窗口状态发布者
    window_status_pub_ = this->create_publisher<std_msgs::msg::Float32MultiArray>("window_status", 10);
    
    // 初始化订阅者
    single_gnss_sub_ = this->create_subscription<nav_msgs::msg::Odometry>(
        gnss_topic, 10, std::bind(&GtsamFusionNode::SingleGNSSCallback, this, std::placeholders::_1));
    single_lidar_sub_ = this->create_subscription<geometry_msgs::msg::PoseWithCovarianceStamped>(
        lidar_topic, 10, std::bind(&GtsamFusionNode::SingleLidarCallback, this, std::placeholders::_1));
    slam_status_ = this->create_subscription<std_msgs::msg::Float32MultiArray>(
        "slam_status", 1, std::bind(&GtsamFusionNode::SlamStatusCallback, this, std::placeholders::_1));
    gnss_status_ = this->create_subscription<std_msgs::msg::UInt32>(
        "by_gnss_pos_type_raw", 1, std::bind(&GtsamFusionNode::GnssStatusCallback, this, std::placeholders::_1));
    gnss_pose_sub_ = this->create_subscription<geometry_msgs::msg::PoseStamped>(
        "/gnss_pose", 10, std::bind(&GtsamFusionNode::gnss_pose_callback, this, std::placeholders::_1));
    gnss_pose_stdev_sub_ = this->create_subscription<std_msgs::msg::Float32>(
        "by_gnss_pos_stdev", 10, std::bind(&GtsamFusionNode::GnssPoseStdevCallback, this, std::placeholders::_1));
    gnss_yaw_stdev_sub_ = this->create_subscription<std_msgs::msg::Float32>(
        "by_gnss_yaw_stdev", 10, std::bind(&GtsamFusionNode::GnssYawStdevCallback, this, std::placeholders::_1));
    calculation_odom_sub_ = this->create_subscription<nav_msgs::msg::Odometry>(
        "calculate_odom", 10, std::bind(&GtsamFusionNode::ValculationOdomCallback, this, std::placeholders::_1));
      
    // 初始化TF广播器 
    tf_broadcaster_ = std::make_shared<tf2_ros::TransformBroadcaster>(this);
    
    // 初始化因子图构建器
    factor_graph_builder_ = std::make_unique<FactorGraphBuilder>();
    
    // 初始化路径消息
    path_msg_.header.frame_id = "map";
    
    RCLCPP_INFO(this->get_logger(), 
               "GTSAM Fusion Node with sliding window (size: %d) initialized", window_size);
  }

private:

  using SyncPolicy = message_filters::sync_policies::ApproximateTime<
      nav_msgs::msg::Odometry, geometry_msgs::msg::PoseWithCovarianceStamped>;
  
  // 订阅者
  message_filters::Subscriber<nav_msgs::msg::Odometry> gnss_sub_;
  message_filters::Subscriber<geometry_msgs::msg::PoseWithCovarianceStamped> lidar_sub_;

  rclcpp::Subscription<nav_msgs::msg::Odometry>::SharedPtr single_gnss_sub_;
  rclcpp::Subscription<nav_msgs::msg::Odometry>::SharedPtr calculation_odom_sub_;
  rclcpp::Subscription<geometry_msgs::msg::PoseWithCovarianceStamped>::SharedPtr single_lidar_sub_;

  std::shared_ptr<message_filters::Synchronizer<SyncPolicy>> sync_;
  rclcpp::Subscription<std_msgs::msg::Float32MultiArray>::SharedPtr slam_status_;
  rclcpp::Subscription<std_msgs::msg::UInt32>::SharedPtr gnss_status_;
  rclcpp::Subscription<geometry_msgs::msg::PoseStamped>::SharedPtr gnss_pose_sub_;
  rclcpp::Subscription<std_msgs::msg::Float32>::SharedPtr gnss_pose_stdev_sub_;
  rclcpp::Subscription<std_msgs::msg::Float32>::SharedPtr gnss_yaw_stdev_sub_;
  
  // 发布者
  rclcpp::Publisher<geometry_msgs::msg::PoseStamped>::SharedPtr pose_pub_;
  rclcpp::Publisher<nav_msgs::msg::Path>::SharedPtr path_pub_;
  rclcpp::Publisher<std_msgs::msg::Float32MultiArray>::SharedPtr window_status_pub_;  
  rclcpp::Publisher<geometry_msgs::msg::PoseWithCovarianceStamped>::SharedPtr initialpose_pub_;

  //tf声明
  std::shared_ptr<tf2_ros::TransformBroadcaster> tf_broadcaster_;  // TF广播器
  std::shared_ptr<tf2_ros::TransformListener> tf_listener_{nullptr};
  std::unique_ptr<tf2_ros::Buffer> tf_buffer_;
  double timeout_seconds = 0;
  std::string target_frame_;
  std::string source_frame_; 
  std::string tf_parent_frame_;  // TF父坐标系
  std::string tf_child_frame_;   // TF子坐标系
  
  // 因子图工具和滑动窗口管理器
  std::unique_ptr<FactorGraphBuilder> factor_graph_builder_;
  std::unique_ptr<SlidingWindowManager> sliding_window_manager_;  // 替换原有的优化器
  
  //Yaml读取的GNSS噪声参数
  geometry_msgs::msg::PoseStamped::SharedPtr last_gnss_pose_;
  std::string gnss_sigma_model;//GNSS噪声模型类型
  std::string main_location_;//主定位方式
  double gnss_stdev_ = 0.1; //GNSS噪声标准差
  double initpose_enabled_;//初始位姿估计开关
  double fitness_score_threshold_ = 2.0;//优化收敛阈值

  // 路径记录
  nav_msgs::msg::Path path_msg_;
  
  // 状态变量
  uint64_t pose_key_ = 0;
  gtsam::Pose3 last_pose_;
  bool has_prior_ = false;
  gtsam::Pose3 odom_pose;
  gtsam::Pose3 optimized_pose;
  
  double gnss_sigma  = 0.1;
  double gnss_yaw_sigma  = 0.1;
  double lidar_sigma = 0.1;
  
  // 噪声模型
  gtsam::SharedNoiseModel gnss_noise_model_;
  gtsam::SharedNoiseModel lidar_noise_model_;
  gtsam::SharedNoiseModel prior_noise_model_;
  gtsam::SharedNoiseModel odom_noise_model_;

  //传感器数据融合定位
  void sensorCallback(
    const nav_msgs::msg::Odometry::ConstSharedPtr& gnss_msg,
    const geometry_msgs::msg::PoseWithCovarianceStamped::ConstSharedPtr& lidar_msg)
  {
    if(main_location_ != "gnss+lidar") return;
    if (!gnss_msg || !lidar_msg) {
      RCLCPP_WARN(this->get_logger(), "Received null message, skipping processing");
      return;
    }
    RCLCPP_INFO(this->get_logger(), "Received gnss and lidar messages");
    gtsamFusion(gnss_msg, lidar_msg, "gnss+lidar");

  }

  // 新增：接收单个GNSS回调
  void SingleGNSSCallback(const nav_msgs::msg::Odometry::ConstSharedPtr& gnss_msg)
  {
    if (!gnss_msg || main_location_ != "gnss") {
        RCLCPP_WARN(this->get_logger(), "Received null message, skipping processing");
        return;
    }
    RCLCPP_INFO(this->get_logger(), "Received single gnss message");
    gtsamFusion(gnss_msg, nullptr, "gnss");
  }

  // 新增：接收单个激光雷达回调
  void SingleLidarCallback(const geometry_msgs::msg::PoseWithCovarianceStamped::ConstSharedPtr& lidar_msg)
  {
    if (!lidar_msg || main_location_ != "lidar") {
        RCLCPP_WARN(this->get_logger(), "Received null message, skipping processing");
        return;
    }
    RCLCPP_INFO(this->get_logger(), "Received single lidar message");
    gtsamFusion(nullptr, lidar_msg, "lidar");
  }

  // odometry回调
  void ValculationOdomCallback(const nav_msgs::msg::Odometry::ConstSharedPtr& odom_msg)
  {
    if (!odom_msg) {
        RCLCPP_WARN(this->get_logger(), "Received null message, skipping processing");
        return;
    }
    odom_pose = poseMsgToGtsam(odom_msg->pose.pose);
  }

  void gtsamFusion(const nav_msgs::msg::Odometry::ConstSharedPtr& gnss_msg,
    const geometry_msgs::msg::PoseWithCovarianceStamped::ConstSharedPtr& lidar_msg ,std::string main_location) {
    try {
        // 转换ROS消息为GTSAM Pose3
        gtsam::Pose3 gnss_pose = poseMsgToGtsam(gnss_msg->pose.pose);
        gtsam::Pose3 lidar_pose = poseMsgToGtsam(lidar_msg->pose.pose);
        
        // 创建本次回调的因子图和初始估计[2](@ref)
        gtsam::NonlinearFactorGraph new_factors;
        gtsam::Values new_values;
        
        // 添加先验因子（第一个位姿）
        if (!has_prior_) {
            gtsam::Vector6 prior_sigmas;
            prior_sigmas << 0.1, 0.1, 0.1, 0.1, 0.1, 0.1;
            auto prior_noise = gtsam::noiseModel::Diagonal::Sigmas(prior_sigmas);
            
            if(main_location == "gnss+lidar" || main_location == "gnss")
            {
              new_factors.addPrior(pose_key_, gnss_pose, prior_noise);
              new_values.insert(pose_key_, gnss_pose);
            
              last_pose_ = gnss_pose;
              has_prior_ = true;
            }
            else if(main_location == "lidar")
            {
              new_factors.addPrior(pose_key_, lidar_pose, prior_noise);
              new_values.insert(pose_key_, lidar_pose);
            
              last_pose_ = lidar_pose;
              has_prior_ = true;
            }
            
            RCLCPP_INFO(this->get_logger(), "Added prior factor at key: %lu", pose_key_);
        } else {

            if(main_location == "gnss+lidar")
            {
               // 添加GNSS因子
               new_factors.add(gtsam::PriorFactor<gtsam::Pose3>(pose_key_, gnss_pose, gnss_noise_model_));
               // 添加激光雷达因子
               new_factors.add(gtsam::PriorFactor<gtsam::Pose3>(pose_key_, lidar_pose, lidar_noise_model_));
            }
            else if(main_location == "gnss")
            {
               // 添加GNSS因子
               new_factors.add(gtsam::PriorFactor<gtsam::Pose3>(pose_key_, gnss_pose, gnss_noise_model_));
            }
            else if(main_location == "lidar")
            {
               // 添加激光雷达因子
               new_factors.add(gtsam::PriorFactor<gtsam::Pose3>(pose_key_, lidar_pose, lidar_noise_model_));
            }
            // 添加里程计因子（相对运动约束）[1](@ref)
            gtsam::Pose3 relative_pose = last_pose_.between(odom_pose);
            new_factors.add(gtsam::BetweenFactor<gtsam::Pose3>(
                pose_key_ - 1, pose_key_, relative_pose, odom_noise_model_));
            
            new_values.insert(pose_key_, lidar_pose);
            last_pose_ = odom_pose;
        }
        
        // 使用滑动窗口管理器进行优化[4](@ref)
        auto window_state = sliding_window_manager_->addNewPose(pose_key_, new_factors, new_values);
        
        // 获取优化结果
        gtsam::Values result = sliding_window_manager_->getCurrentEstimate();
        
        // 发布优化后的位姿
        if (result.exists(pose_key_)) {
            optimized_pose = result.at<gtsam::Pose3>(pose_key_);
            publishOptimizedPose(optimized_pose, gnss_msg->header.stamp);
            publishTfTransform(optimized_pose, gnss_msg->header);  // 新增：发布TF变换
            
            // 发布窗口状态信息
            publishWindowStatus(window_state);
            
            RCLCPP_DEBUG(this->get_logger(), 
                        "Optimized pose at key: %lu, window size: %zu/%zu", 
                        pose_key_, window_state.current_size, window_state.window_size);
        }
        
        pose_key_++;
        
    } catch (const std::exception& e) {
        RCLCPP_FATAL(this->get_logger(), "Exception in sensorCallback: %s", e.what());
    }
  }

  // 发布窗口状态信息
  void publishWindowStatus(const SlidingWindowManager::WindowState& state) {
    std_msgs::msg::Float32MultiArray status_msg;
    status_msg.data.push_back(static_cast<float>(state.current_size));
    status_msg.data.push_back(static_cast<float>(state.window_size));
    status_msg.data.push_back(static_cast<float>(state.marginalizable_keys.size()));
    status_msg.data.push_back(static_cast<float>(state.current_key));
    
    window_status_pub_->publish(status_msg);
  }

  gtsam::Pose3 poseMsgToGtsam(const geometry_msgs::msg::Pose& pose_msg) {
    gtsam::Point3 translation(pose_msg.position.x, pose_msg.position.y, pose_msg.position.z);
    gtsam::Rot3 rotation(gtsam::Quaternion(
        pose_msg.orientation.w, pose_msg.orientation.x, 
        pose_msg.orientation.y, pose_msg.orientation.z));
    return gtsam::Pose3(rotation, translation);
  }
  
  // 发布优化后的位姿
  void publishOptimizedPose(const gtsam::Pose3& pose, const builtin_interfaces::msg::Time& stamp) {
    try {
        geometry_msgs::msg::PoseStamped pose_msg;
        pose_msg.header.stamp = stamp;
        pose_msg.header.frame_id = "map";
        
        pose_msg.pose.position.x = pose.translation().x();
        pose_msg.pose.position.y = pose.translation().y();
        pose_msg.pose.position.z = pose.translation().z();
        
        gtsam::Quaternion quat = pose.rotation().toQuaternion();
        pose_msg.pose.orientation.x = quat.x();
        pose_msg.pose.orientation.y = quat.y();
        pose_msg.pose.orientation.z = quat.z();
        pose_msg.pose.orientation.w = quat.w();
        
        pose_pub_->publish(pose_msg);
        
        path_msg_.header.stamp = stamp;
        path_msg_.poses.push_back(pose_msg);
        
        if (path_msg_.poses.size() > 1000) {
            path_msg_.poses.erase(path_msg_.poses.begin());
        }
        
        path_pub_->publish(path_msg_);
        
    } catch (const std::exception& e) {
        RCLCPP_ERROR(this->get_logger(), "Error in publishOptimizedPose: %s", e.what());
    }
  }
  
  // SLAM状态回调
  void SlamStatusCallback(const std_msgs::msg::Float32MultiArray::SharedPtr paraMsg) {
    float fitness_score = paraMsg->data[1];
    RCLCPP_INFO(this->get_logger(), "fitness_score: %f ,initpose_enabled_: %f ,threshold_: %f", fitness_score, initpose_enabled_ ,fitness_score_threshold_);
    lidar_sigma = fitness_score;
    lidar_noise_model_ = gtsam::noiseModel::Diagonal::Sigmas(
        (gtsam::Vector(6) << lidar_sigma, lidar_sigma, lidar_sigma, 
                            lidar_sigma, lidar_sigma, lidar_sigma).finished());
    // 发布初始位姿
     if(fitness_score > fitness_score_threshold_)
     {
        if(gnss_stdev_ < initpose_enabled_) publish_init_pose();
     }
  }
  
  // GNSS状态回调
  void GnssStatusCallback(const std_msgs::msg::UInt32::SharedPtr paraMsg) {
    if(gnss_sigma_model != "type") return;
    
    uint32_t gnss_status = paraMsg->data;
    RCLCPP_INFO(this->get_logger(), "gnss_status: %d", gnss_status);
    if(gnss_status == 50) gnss_sigma = 0.01;
    else if(gnss_status == 34) gnss_sigma = 1; 
    else gnss_sigma = 10;
    gnss_noise_model_ = gtsam::noiseModel::Diagonal::Sigmas(
        (gtsam::Vector(6) << gnss_sigma, gnss_sigma, gnss_sigma, 
                            gnss_sigma, gnss_sigma, gnss_sigma).finished());
  }

  // 发布初始位姿
  void publish_init_pose()
  {
    if (!last_gnss_pose_) return;
    
    // 发布PoseWithCovarianceStamped格式的初始位姿
    auto init_pose = geometry_msgs::msg::PoseWithCovarianceStamped();
    init_pose.header.stamp = this->now();
    init_pose.header.frame_id = "map";
    init_pose.pose.pose = last_gnss_pose_->pose;
             
    // 设置合理的协方差值（对角线元素）
    for (int i = 0; i < 36; i++) {
         init_pose.pose.covariance[i] = (i%7 == 0) ? 0.5 : 0.0;
    }
            
    initialpose_pub_->publish(init_pose);
    RCLCPP_INFO(this->get_logger(), "Published /initialpose for SLAM initialization");
  }

  // 接收GNSS位姿
  void gnss_pose_callback(const geometry_msgs::msg::PoseStamped::SharedPtr msg)
  {
    last_gnss_pose_ = msg;
  }
  //gnss经纬高标准差最大值回调
  void GnssPoseStdevCallback(const std_msgs::msg::Float32::SharedPtr msg)
  {
    if(gnss_sigma_model != "sigma") return;

    //gnss_stdev_ = msg->data;
    gnss_sigma = msg->data;
    RCLCPP_INFO(this->get_logger(), "gnss_stdev: %f", gnss_sigma);
    gnss_noise_model_ = gtsam::noiseModel::Diagonal::Sigmas(
        (gtsam::Vector(6) << gnss_sigma, gnss_sigma, gnss_sigma, 
                            gnss_yaw_sigma, gnss_yaw_sigma, gnss_yaw_sigma).finished());
  }
  //gnss姿态标准差最大值回调
  void GnssYawStdevCallback(const std_msgs::msg::Float32::SharedPtr msg)
  {
    gnss_yaw_sigma = msg->data * 3.1415926 / 180.0;
    RCLCPP_INFO(this->get_logger(), "gnss_yaw_stdev: %f", msg->data);
  }

  // 新增：发布TF变换函数 
  void publishTfTransform(const gtsam::Pose3& pose, const std_msgs::msg::Header& header) {
    try {
        geometry_msgs::msg::TransformStamped transform_stamped;
        
        // 设置时间戳和坐标系ID [1](@ref)
        transform_stamped.header.stamp = header.stamp;
        transform_stamped.header.frame_id = tf_parent_frame_;  // 父坐标系，通常是"map"或"odom"
        transform_stamped.child_frame_id = tf_child_frame_;    // 子坐标系，通常是"base_link_optimized"
        
        // 设置平移
        transform_stamped.transform.translation.x = pose.translation().x();
        transform_stamped.transform.translation.y = pose.translation().y();
        transform_stamped.transform.translation.z = pose.translation().z();
        
        // 设置旋转（GTSAM四元数转换为ROS四元数）
        gtsam::Quaternion quat = pose.rotation().toQuaternion();
        transform_stamped.transform.rotation.x = quat.x();
        transform_stamped.transform.rotation.y = quat.y();
        transform_stamped.transform.rotation.z = quat.z();
        transform_stamped.transform.rotation.w = quat.w();
        
        // 发布TF变换 [5](@ref)
        tf_broadcaster_->sendTransform(transform_stamped);
        
        RCLCPP_DEBUG(this->get_logger(), 
                    "Published TF transform: %s -> %s", 
                    tf_parent_frame_.c_str(), tf_child_frame_.c_str());
                    
    } catch (const std::exception& e) {
        RCLCPP_ERROR(this->get_logger(), "Error publishing TF transform: %s", e.what());
    }
  }
    
};

int main(int argc, char** argv)
{
  rclcpp::init(argc, argv);
  auto node = std::make_shared<GtsamFusionNode>();
  rclcpp::spin(node);
  rclcpp::shutdown();
  return 0;
}
