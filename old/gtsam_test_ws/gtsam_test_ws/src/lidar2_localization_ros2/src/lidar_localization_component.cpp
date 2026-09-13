#include <lidar2_localization/lidar_localization_component.hpp>
#include <fstream>
#include <vector>
PCLLocalization::PCLLocalization(const rclcpp::NodeOptions & options)
: rclcpp_lifecycle::LifecycleNode("lidar_localization", options),
  clock_(RCL_ROS_TIME),
  tfbuffer_(std::make_shared<rclcpp::Clock>(clock_)),
  tflistener_(tfbuffer_),
  broadcaster_(this)
{
  declare_parameter("global_frame_id", "map");
  declare_parameter("odom_frame_id", "odom");
  declare_parameter("base_frame_id", "base_link");
  declare_parameter("registration_method", "NDT");
  declare_parameter("score_threshold", 2.0);
  declare_parameter("ndt_resolution", 1.0);
  declare_parameter("ndt_step_size", 0.1);
  declare_parameter("transform_epsilon", 0.01);
  declare_parameter("voxel_leaf_size", 0.2);
  declare_parameter("scan_max_range", 100.0);
  declare_parameter("scan_min_range", 1.0);
  declare_parameter("scan_period", 0.1);
  declare_parameter("use_pcd_map", false);
  declare_parameter("map_path", "/map/map.pcd");
  declare_parameter("set_initial_pose", false);
  declare_parameter("initial_pose_x", 0.0);
  declare_parameter("initial_pose_y", 0.0);
  declare_parameter("initial_pose_z", 0.0);
  declare_parameter("initial_pose_qx", 0.0);
  declare_parameter("initial_pose_qy", 0.0);
  declare_parameter("initial_pose_qz", 0.0);
  declare_parameter("initial_pose_qw", 1.0);
  declare_parameter("use_odom", false);
  declare_parameter("use_imu", false);
  declare_parameter("enable_debug", false);
  declare_parameter("param_yaml_path", "/home/lyrobot012/gtsam_test_wsly004/gtsam_test_ws/src/lidar2_localization_ros2/param/localization.yaml");
}

using CallbackReturn = rclcpp_lifecycle::node_interfaces::LifecycleNodeInterface::CallbackReturn;

CallbackReturn PCLLocalization::on_configure(const rclcpp_lifecycle::State &)
{
  RCLCPP_INFO(get_logger(), "正在配置...");

  initializeParameters();
  initializePubSub();
  initializeRegistration();

  path_ptr_ = std::make_shared<nav_msgs::msg::Path>();
  path_ptr_->header.frame_id = global_frame_id_;

  RCLCPP_INFO(get_logger(), "配置完成");
  return CallbackReturn::SUCCESS;
}

CallbackReturn PCLLocalization::on_activate(const rclcpp_lifecycle::State &)
{
  RCLCPP_INFO(get_logger(), "正在激活...");

  pose_pub_->on_activate();
  path_pub_->on_activate();
  odom_pub_->on_activate();
  initial_map_pub_->on_activate();

  // 如果设置了初始位姿，则发布初始位姿
  if (set_initial_pose_) {
    auto msg = std::make_shared<geometry_msgs::msg::PoseWithCovarianceStamped>();

    msg->header.stamp = now();
    msg->header.frame_id = global_frame_id_;
    msg->pose.pose.position.x = initial_pose_x_;
    msg->pose.pose.position.y = initial_pose_y_;
    msg->pose.pose.position.z = initial_pose_z_;
    msg->pose.pose.orientation.x = initial_pose_qx_;
    msg->pose.pose.orientation.y = initial_pose_qy_;
    msg->pose.pose.orientation.z = initial_pose_qz_;
    msg->pose.pose.orientation.w = initial_pose_qw_;

    geometry_msgs::msg::PoseStamped::SharedPtr pose_stamped(new geometry_msgs::msg::PoseStamped);
    pose_stamped->header.stamp = msg->header.stamp;
    pose_stamped->header.frame_id = global_frame_id_;
    pose_stamped->pose = msg->pose.pose;
    path_ptr_->poses.push_back(*pose_stamped);

    initialPoseReceived(msg);
  }

  // 如果使用PCD地图，则加载并发布
  if (use_pcd_map_) {
    pcl::PointCloud<pcl::PointXYZI>::Ptr map_cloud_ptr(new pcl::PointCloud<pcl::PointXYZI>);
    pcl::io::loadPCDFile(map_path_, *map_cloud_ptr);
    RCLCPP_INFO(get_logger(), "地图点云数量: %ld", map_cloud_ptr->size());

    sensor_msgs::msg::PointCloud2::SharedPtr map_msg_ptr(new sensor_msgs::msg::PointCloud2);
    pcl::toROSMsg(*map_cloud_ptr, *map_msg_ptr);
    map_msg_ptr->header.frame_id = global_frame_id_;
    initial_map_pub_->publish(*map_msg_ptr);
    RCLCPP_INFO(get_logger(), "初始地图已发布");

    if (registration_method_ == "GICP" || registration_method_ == "GICP_OMP") {
      pcl::PointCloud<pcl::PointXYZI>::Ptr filtered_cloud_ptr(new pcl::PointCloud<pcl::PointXYZI>());
      voxel_grid_filter_.setInputCloud(map_cloud_ptr);
      voxel_grid_filter_.filter(*filtered_cloud_ptr);
      registration_->setInputTarget(filtered_cloud_ptr);
    } else {
      registration_->setInputTarget(map_cloud_ptr);
    }

    map_recieved_ = true;
  }

    RCLCPP_INFO(get_logger(), "激活完成");
  return CallbackReturn::SUCCESS;
}

CallbackReturn PCLLocalization::on_deactivate(const rclcpp_lifecycle::State &)
{
  RCLCPP_INFO(get_logger(), "正在停用...");

  pose_pub_->on_deactivate();
  path_pub_->on_deactivate();
  odom_pub_->on_deactivate();
  initial_map_pub_->on_deactivate();

  RCLCPP_INFO(get_logger(), "停用完成");
  return CallbackReturn::SUCCESS;
}

CallbackReturn PCLLocalization::on_cleanup(const rclcpp_lifecycle::State &)
{
  RCLCPP_INFO(get_logger(), "正在清理...");

  initial_pose_sub_.reset();
  initial_map_pub_.reset();
  path_pub_.reset();
  pose_pub_.reset();
  odom_sub_.reset();
  cloud_sub_.reset();
  imu_sub_.reset();
  map_path_sub_.reset();
  odom_pub_.reset();

  RCLCPP_INFO(get_logger(), "清理完成");
  return CallbackReturn::SUCCESS;
}

CallbackReturn PCLLocalization::on_shutdown(const rclcpp_lifecycle::State & state)
{
  RCLCPP_INFO(get_logger(), "正在关闭，状态: %s", state.label().c_str());

  return CallbackReturn::SUCCESS;
}

CallbackReturn PCLLocalization::on_error(const rclcpp_lifecycle::State & state)
{
  RCLCPP_FATAL(get_logger(), "错误处理，状态: %s", state.label().c_str());

  return CallbackReturn::SUCCESS;
}

void PCLLocalization::initializeParameters()
{
  RCLCPP_INFO(get_logger(), "正在初始化参数...");
  get_parameter("global_frame_id", global_frame_id_);
  get_parameter("odom_frame_id", odom_frame_id_);
  get_parameter("base_frame_id", base_frame_id_);
  get_parameter("registration_method", registration_method_);
  get_parameter("score_threshold", score_threshold_);
  get_parameter("ndt_resolution", ndt_resolution_);
  get_parameter("ndt_step_size", ndt_step_size_);
  get_parameter("ndt_num_threads", ndt_num_threads_);
  get_parameter("transform_epsilon", transform_epsilon_);
  get_parameter("voxel_leaf_size", voxel_leaf_size_);
  get_parameter("scan_max_range", scan_max_range_);
  get_parameter("scan_min_range", scan_min_range_);
  get_parameter("scan_period", scan_period_);
  get_parameter("use_pcd_map", use_pcd_map_);
  get_parameter("map_path", map_path_);
  get_parameter("set_initial_pose", set_initial_pose_);
  get_parameter("initial_pose_x", initial_pose_x_);
  get_parameter("initial_pose_y", initial_pose_y_);
  get_parameter("initial_pose_z", initial_pose_z_);
  get_parameter("initial_pose_qx", initial_pose_qx_);
  get_parameter("initial_pose_qy", initial_pose_qy_);
  get_parameter("initial_pose_qz", initial_pose_qz_);
  get_parameter("initial_pose_qw", initial_pose_qw_);
  get_parameter("use_odom", use_odom_);
  get_parameter("use_imu", use_imu_);
  get_parameter("enable_debug", enable_debug_);
  get_parameter("param_yaml_path", param_yaml_path_);

  // 打印参数配置
  RCLCPP_INFO(get_logger(),"全局坐标系: %s", global_frame_id_.c_str());
  RCLCPP_INFO(get_logger(),"里程计坐标系: %s", odom_frame_id_.c_str());
  RCLCPP_INFO(get_logger(),"基座坐标系: %s", base_frame_id_.c_str());
  RCLCPP_INFO(get_logger(),"配准方法: %s", registration_method_.c_str());
  RCLCPP_INFO(get_logger(),"NDT分辨率: %lf", ndt_resolution_);
  RCLCPP_INFO(get_logger(),"NDT步长: %lf", ndt_step_size_);
  RCLCPP_INFO(get_logger(),"NDT线程数: %d", ndt_num_threads_);
  RCLCPP_INFO(get_logger(),"变换误差: %lf", transform_epsilon_);
  RCLCPP_INFO(get_logger(),"体素叶大小: %lf", voxel_leaf_size_);
  RCLCPP_INFO(get_logger(),"扫描最大范围: %lf", scan_max_range_);
  RCLCPP_INFO(get_logger(),"扫描最小范围: %lf", scan_min_range_);
  RCLCPP_INFO(get_logger(),"扫描周期: %lf", scan_period_);
  RCLCPP_INFO(get_logger(),"使用PCD地图: %d", use_pcd_map_);
  RCLCPP_INFO(get_logger(),"地图路径: %s", map_path_.c_str());
  RCLCPP_INFO(get_logger(),"设置初始位姿: %d", set_initial_pose_);
  RCLCPP_INFO(get_logger(),"使用里程计: %d", use_odom_);
  RCLCPP_INFO(get_logger(),"使用IMU: %d", use_imu_);
  RCLCPP_INFO(get_logger(),"启用调试: %d", enable_debug_);
  RCLCPP_INFO(get_logger(),"参数YAML路径: %s", param_yaml_path_.c_str());
}

void PCLLocalization::initializePubSub()
{
  RCLCPP_INFO(get_logger(), "正在初始化发布订阅...");

  // 发布位姿
  pose_pub_ = create_publisher<geometry_msgs::msg::PoseWithCovarianceStamped>(
    "pcl_pose",
    rclcpp::QoS(rclcpp::KeepLast(1)).transient_local().reliable());
    
  // 发布里程计
  odom_pub_ = create_publisher<nav_msgs::msg::Odometry>(
    "pcl_odom",
    rclcpp::QoS(rclcpp::KeepLast(1)).reliable());
    
  // 发布轨迹
  path_pub_ = create_publisher<nav_msgs::msg::Path>(
    "path",
    rclcpp::QoS(rclcpp::KeepLast(1)).transient_local().reliable());
    
  // 发布雷达定位状态
  slam_status_pub_ = this->create_publisher<std_msgs::msg::Float32MultiArray>("slam_status", 10);
  slam_pos_type_pub_ = this->create_publisher<std_msgs::msg::Bool>("slam_pos_type", 10);

  // 发布初始地图
  initial_map_pub_ = create_publisher<sensor_msgs::msg::PointCloud2>(
    "initial_map",
    rclcpp::QoS(rclcpp::KeepLast(1)).transient_local().reliable());

  // 订阅初始位姿
  initial_pose_sub_ = create_subscription<geometry_msgs::msg::PoseWithCovarianceStamped>(
    "initialpose", rclcpp::SystemDefaultsQoS(),
    std::bind(&PCLLocalization::initialPoseReceived, this, std::placeholders::_1));

  // 订阅地图
  map_sub_ = create_subscription<sensor_msgs::msg::PointCloud2>(
    "map", rclcpp::QoS(rclcpp::KeepLast(1)).transient_local().reliable(),
    std::bind(&PCLLocalization::mapReceived, this, std::placeholders::_1));

  // 订阅里程计
  odom_sub_ = create_subscription<nav_msgs::msg::Odometry>(
    "odom", rclcpp::SensorDataQoS(),
    std::bind(&PCLLocalization::odomReceived, this, std::placeholders::_1));

  // 订阅点云
  cloud_sub_ = create_subscription<sensor_msgs::msg::PointCloud2>(
    "velodyne_points", rclcpp::SensorDataQoS(),
    std::bind(&PCLLocalization::cloudReceived, this, std::placeholders::_1));

  // 订阅IMU
  imu_sub_ = create_subscription<sensor_msgs::msg::Imu>(
    "imu", rclcpp::SensorDataQoS(),
    std::bind(&PCLLocalization::imuReceived, this, std::placeholders::_1));

  // 订阅地图路径
  map_path_sub_ = create_subscription<std_msgs::msg::String>(
    "map_path", rclcpp::QoS(rclcpp::KeepLast(1)).reliable(),
    std::bind(&PCLLocalization::mapPathReceived, this, std::placeholders::_1));

  RCLCPP_INFO(get_logger(), "发布订阅初始化完成");
}

void PCLLocalization::initializeRegistration()
{
  RCLCPP_INFO(get_logger(), "正在初始化配准...");

  if (registration_method_ == "GICP") {
    boost::shared_ptr<pcl::GeneralizedIterativeClosestPoint<pcl::PointXYZI, pcl::PointXYZI>> gicp(
      new pcl::GeneralizedIterativeClosestPoint<pcl::PointXYZI, pcl::PointXYZI>());
    gicp->setTransformationEpsilon(transform_epsilon_);
    registration_ = gicp;
  }
  else if (registration_method_ == "NDT") {
    boost::shared_ptr<pcl::NormalDistributionsTransform<pcl::PointXYZI, pcl::PointXYZI>> ndt(
      new pcl::NormalDistributionsTransform<pcl::PointXYZI, pcl::PointXYZI>());
    ndt->setStepSize(ndt_step_size_);
    ndt->setResolution(ndt_resolution_);
    ndt->setTransformationEpsilon(transform_epsilon_);
    registration_ = ndt;
  }
  else if (registration_method_ == "NDT_OMP") {
    pclomp::NormalDistributionsTransform<pcl::PointXYZI, pcl::PointXYZI>::Ptr ndt_omp(
      new pclomp::NormalDistributionsTransform<pcl::PointXYZI, pcl::PointXYZI>());
    ndt_omp->setStepSize(ndt_step_size_);
    ndt_omp->setResolution(ndt_resolution_);
    ndt_omp->setTransformationEpsilon(transform_epsilon_);
    if (ndt_num_threads_ > 0) {
      ndt_omp->setNumThreads(ndt_num_threads_);
    } else {
      ndt_omp->setNumThreads(omp_get_max_threads());
    }
    registration_ = ndt_omp;
  }
  else if (registration_method_ == "GICP_OMP") {
    pclomp::GeneralizedIterativeClosestPoint<pcl::PointXYZI, pcl::PointXYZI>::Ptr gicp_omp(
      new pclomp::GeneralizedIterativeClosestPoint<pcl::PointXYZI, pcl::PointXYZI>());
    gicp_omp->setTransformationEpsilon(transform_epsilon_);
    registration_ = gicp_omp;
  }
  else {
    RCLCPP_ERROR(get_logger(), "无效的配准方法");
    exit(EXIT_FAILURE);
  }


  voxel_grid_filter_.setLeafSize(voxel_leaf_size_, voxel_leaf_size_, voxel_leaf_size_);
  RCLCPP_INFO(get_logger(), "配准初始化完成");
}

void PCLLocalization::initialPoseReceived(const geometry_msgs::msg::PoseWithCovarianceStamped::SharedPtr msg)
{
  RCLCPP_INFO(get_logger(), "接收到初始位姿");
  // 检查坐标系是否匹配
  if (msg->header.frame_id != global_frame_id_) {
    RCLCPP_WARN(this->get_logger(), "初始位姿坐标系与全局坐标系不匹配");
    return;
  }
  initialpose_recieved_ = true;
  corrent_pose_with_cov_stamped_ptr_ = msg;
  pose_pub_->publish(*corrent_pose_with_cov_stamped_ptr_);

  cloudReceived(last_scan_ptr_);
  RCLCPP_INFO(get_logger(), "初始位姿处理完成");
}

void PCLLocalization::mapReceived(const sensor_msgs::msg::PointCloud2::SharedPtr msg)
{
  RCLCPP_INFO(get_logger(), "接收到地图");
  pcl::PointCloud<pcl::PointXYZI>::Ptr map_cloud_ptr(new pcl::PointCloud<pcl::PointXYZI>);

  // 检查坐标系是否匹配
  if (msg->header.frame_id != global_frame_id_) {
    RCLCPP_WARN(this->get_logger(), "地图坐标系与全局坐标系不匹配");
    return;
  }

  pcl::fromROSMsg(*msg, *map_cloud_ptr);

  if (registration_method_ == "GICP" || registration_method_ == "GICP_OMP") {
    pcl::PointCloud<pcl::PointXYZI>::Ptr filtered_cloud_ptr(new pcl::PointCloud<pcl::PointXYZI>());
    voxel_grid_filter_.setInputCloud(map_cloud_ptr);
    voxel_grid_filter_.filter(*filtered_cloud_ptr);
    registration_->setInputTarget(filtered_cloud_ptr);

  } else {
    registration_->setInputTarget(map_cloud_ptr);
  }

  map_recieved_ = true;
  RCLCPP_INFO(get_logger(), "地图处理完成");
}

void PCLLocalization::odomReceived(const nav_msgs::msg::Odometry::ConstSharedPtr msg)
{
  if (!use_odom_) {return;}
  if (enable_debug_) {
    RCLCPP_INFO(get_logger(), "接收到里程计数据");
  }

  double current_odom_received_time = msg->header.stamp.sec +
    msg->header.stamp.nanosec * 1e-9;
  double dt_odom = current_odom_received_time - last_odom_received_time_;
  last_odom_received_time_ = current_odom_received_time;
  // 检查时间间隔
  if (dt_odom > 1.0 /* [秒] */) {
    RCLCPP_WARN(this->get_logger(), "里程计时间间隔过大");
    return;
  }
  if (dt_odom < 0.0 /* [秒] */) {
    RCLCPP_WARN(this->get_logger(), "里程计时间间隔为负");
    return;
  }

  tf2::Quaternion previous_quat_tf;
  double roll, pitch, yaw;
  tf2::fromMsg(corrent_pose_with_cov_stamped_ptr_->pose.pose.orientation, previous_quat_tf);

  tf2::Matrix3x3(previous_quat_tf).getRPY(roll, pitch, yaw);

  roll += msg->twist.twist.angular.x * dt_odom;
  pitch += msg->twist.twist.angular.y * dt_odom;
  yaw += msg->twist.twist.angular.z * dt_odom;

  Eigen::Quaterniond quat_eig =
    Eigen::AngleAxisd(roll, Eigen::Vector3d::UnitX()) *
    Eigen::AngleAxisd(pitch, Eigen::Vector3d::UnitY()) *
    Eigen::AngleAxisd(yaw, Eigen::Vector3d::UnitZ());

  geometry_msgs::msg::Quaternion quat_msg = tf2::toMsg(quat_eig);

  Eigen::Vector3d odom{
    msg->twist.twist.linear.x,
    msg->twist.twist.linear.y,
    msg->twist.twist.linear.z};
  Eigen::Vector3d delta_position = quat_eig.matrix() * dt_odom * odom;

  corrent_pose_with_cov_stamped_ptr_->pose.pose.position.x += delta_position.x();
  corrent_pose_with_cov_stamped_ptr_->pose.pose.position.y += delta_position.y();
  corrent_pose_with_cov_stamped_ptr_->pose.pose.position.z += delta_position.z();
  corrent_pose_with_cov_stamped_ptr_->pose.pose.orientation = quat_msg;
}

void PCLLocalization::mapPathReceived(const std_msgs::msg::String::ConstSharedPtr msg)
{
  RCLCPP_INFO(get_logger(), "接收到地图路径: %s", msg->data.c_str());
  map_path_ = msg->data;
  RCLCPP_INFO(get_logger(), "地图路径已更新为: %s", map_path_.c_str());

  // 更新YAML文件
  std::ifstream infile(param_yaml_path_);
  if (!infile.is_open()) {
    RCLCPP_ERROR(get_logger(), "无法打开YAML文件: %s", param_yaml_path_.c_str());
    return;
  }

  std::vector<std::string> lines;
  std::string line;
  while (std::getline(infile, line)) {
    lines.push_back(line);
  }
  infile.close();

  // 查找并更新map_path行
  bool found = false;
  for (size_t i = 0; i < lines.size(); ++i) {
    // 查找包含"map_path:"且未被注释的行
    size_t pos = lines[i].find("map_path:");
    if (pos != std::string::npos) {
      // 检查该行是否不是注释（不以#开头）
      std::string before = lines[i].substr(0, pos);
      // 去除前面的空白字符
      size_t start = before.find_first_not_of(" \t");
      if (start == std::string::npos || before[start] != '#') {
        // 替换值
        size_t value_start = lines[i].find('"', pos);
        size_t value_end = lines[i].find('"', value_start + 1);
        if (value_start != std::string::npos && value_end != std::string::npos) {
          lines[i] = lines[i].substr(0, value_start + 1) + map_path_ + lines[i].substr(value_end);
          found = true;
          break;
        }
      }
    }
  }

  if (!found) {
    RCLCPP_WARN(get_logger(), "在YAML文件中未找到map_path配置项");
    return;
  }

  std::ofstream outfile(param_yaml_path_);
  if (!outfile.is_open()) {
    RCLCPP_ERROR(get_logger(), "无法写入YAML文件: %s", param_yaml_path_.c_str());
    return;
  }

  for (const auto& l : lines) {
    outfile << l << "\n";
  }
  outfile.close();

  RCLCPP_INFO(get_logger(), "YAML文件更新成功: %s", param_yaml_path_.c_str());
}

void PCLLocalization::imuReceived(const sensor_msgs::msg::Imu::ConstSharedPtr msg)
{
  if (!use_imu_) {return;}

  sensor_msgs::msg::Imu tf_converted_imu;

  try {
    const geometry_msgs::msg::TransformStamped transform = tfbuffer_.lookupTransform(
     base_frame_id_, msg->header.frame_id, tf2::TimePointZero);

    geometry_msgs::msg::Vector3Stamped angular_velocity, linear_acceleration, transformed_angular_velocity, transformed_linear_acceleration;
    geometry_msgs::msg::Quaternion  transformed_quaternion;

    angular_velocity.header = msg->header;
    angular_velocity.vector = msg->angular_velocity;
    linear_acceleration.header = msg->header;
    linear_acceleration.vector = msg->linear_acceleration;

    tf2::doTransform(angular_velocity, transformed_angular_velocity, transform);
    tf2::doTransform(linear_acceleration, transformed_linear_acceleration, transform);

    tf_converted_imu.angular_velocity = transformed_angular_velocity.vector;
    tf_converted_imu.linear_acceleration = transformed_linear_acceleration.vector;
    tf_converted_imu.orientation = transformed_quaternion;

  }
  catch (tf2::TransformException& ex)
  {
    std::cout << "坐标变换查找失败" << std::endl;
    RCLCPP_WARN(this->get_logger(), "坐标变换查找失败");
    return;
  }

  Eigen::Vector3f angular_velo{tf_converted_imu.angular_velocity.x, tf_converted_imu.angular_velocity.y,
    tf_converted_imu.angular_velocity.z};
  Eigen::Vector3f acc{tf_converted_imu.linear_acceleration.x, tf_converted_imu.linear_acceleration.y, tf_converted_imu.linear_acceleration.z};
  Eigen::Quaternionf quat{msg->orientation.w, msg->orientation.x, msg->orientation.y,
    msg->orientation.z};
  double imu_time = msg->header.stamp.sec +
    msg->header.stamp.nanosec * 1e-9;

  lidar_undistortion_.getImu(angular_velo, acc, quat, imu_time);

}

void PCLLocalization::cloudReceived(const sensor_msgs::msg::PointCloud2::ConstSharedPtr msg)
{
  if (!map_recieved_ || !initialpose_recieved_) {return;}
  if (enable_debug_) {
    RCLCPP_INFO(get_logger(), "接收到点云数据");
  }
  pcl::PointCloud<pcl::PointXYZI>::Ptr cloud_ptr(new pcl::PointCloud<pcl::PointXYZI>);
  pcl::fromROSMsg(*msg, *cloud_ptr);

  // 使用IMU进行去畸变
  if (use_imu_) {
    double received_time = msg->header.stamp.sec +
      msg->header.stamp.nanosec * 1e-9;
    lidar_undistortion_.adjustDistortion(cloud_ptr, received_time);
  }

  // 将点云转换到基座坐标系
  try {
    const geometry_msgs::msg::TransformStamped transform =
      tfbuffer_.lookupTransform(base_frame_id_, msg->header.frame_id, tf2::TimePointZero);

    Eigen::Affine3d tf_eigen = tf2::transformToEigen(transform);
    pcl::transformPointCloud(*cloud_ptr, *cloud_ptr, tf_eigen.matrix().cast<float>());
  } catch (tf2::TransformException &ex) {
    RCLCPP_WARN(
      this->get_logger(),
      "点云坐标变换失败，从 %s 到 %s: %s",
      msg->header.frame_id.c_str(),
      base_frame_id_.c_str(),
      ex.what());
    return;
  }


  pcl::PointCloud<pcl::PointXYZI>::Ptr filtered_cloud_ptr(new pcl::PointCloud<pcl::PointXYZI>());
  voxel_grid_filter_.setInputCloud(cloud_ptr);
  voxel_grid_filter_.filter(*filtered_cloud_ptr);

  double r;
  pcl::PointCloud<pcl::PointXYZI> tmp;
  for (const auto & p : filtered_cloud_ptr->points) {
    r = sqrt(pow(p.x, 2.0) + pow(p.y, 2.0));
    if (scan_min_range_ < r && r < scan_max_range_) {
      tmp.push_back(p);
    }
  }
  pcl::PointCloud<pcl::PointXYZI>::Ptr tmp_ptr(new pcl::PointCloud<pcl::PointXYZI>(tmp));
  registration_->setInputSource(tmp_ptr);

  Eigen::Affine3d affine;
  tf2::fromMsg(corrent_pose_with_cov_stamped_ptr_->pose.pose, affine);

  Eigen::Matrix4f init_guess = affine.matrix().cast<float>();

  pcl::PointCloud<pcl::PointXYZI>::Ptr output_cloud(new pcl::PointCloud<pcl::PointXYZI>);
  rclcpp::Clock system_clock;
  rclcpp::Time time_align_start = system_clock.now();
  registration_->align(*output_cloud, init_guess);
  rclcpp::Time time_align_end = system_clock.now();

  bool has_converged = registration_->hasConverged();
  float fitness_score = registration_->getFitnessScore();
  
  float has_converged_d = has_converged;
  float d_time = time_align_end.seconds() - time_align_start.seconds();
  
  RCLCPP_INFO(this->get_logger(), "LiDAR SLAM状态: 是否收敛 %f, 匹配得分 %f, 耗时 %f 秒",
              has_converged_d, fitness_score, d_time );
              
  // 发布SLAM状态
  auto array_msg = std_msgs::msg::Float32MultiArray();
  array_msg.data = {has_converged, fitness_score, d_time};
  slam_status_pub_->publish(array_msg);
  
  bool slam_pose_type = false;
  if( fitness_score < 0.5 )
  {
    slam_pose_type = true;
  }
  // 发布当前模式
  auto type_msg = std_msgs::msg::Bool();
  type_msg.data = slam_pose_type;
  slam_pos_type_pub_->publish(type_msg);
  
  
  if (!has_converged) {
    RCLCPP_WARN(get_logger(), "配准未收敛");
    return;
  }
  if (fitness_score > score_threshold_) {
    RCLCPP_WARN(get_logger(), "匹配得分超过阈值 %lf", score_threshold_);
  }

  Eigen::Matrix4f final_transformation = registration_->getFinalTransformation();
  Eigen::Matrix3d rot_mat = final_transformation.block<3, 3>(0, 0).cast<double>();
  Eigen::Quaterniond quat_eig(rot_mat);
  geometry_msgs::msg::Quaternion quat_msg = tf2::toMsg(quat_eig);

  corrent_pose_with_cov_stamped_ptr_->header.stamp = msg->header.stamp;
  corrent_pose_with_cov_stamped_ptr_->header.frame_id = global_frame_id_;
  corrent_pose_with_cov_stamped_ptr_->pose.pose.position.x = static_cast<double>(final_transformation(0, 3));
  corrent_pose_with_cov_stamped_ptr_->pose.pose.position.y = static_cast<double>(final_transformation(1, 3));
  corrent_pose_with_cov_stamped_ptr_->pose.pose.position.z = static_cast<double>(final_transformation(2, 3));
  corrent_pose_with_cov_stamped_ptr_->pose.pose.orientation = quat_msg;
  pose_pub_->publish(*corrent_pose_with_cov_stamped_ptr_);
  
  //-----------------
  // 新增：发布odom消息
  nav_msgs::msg::Odometry odom_msg;
  odom_msg.header.stamp = msg->header.stamp;
  odom_msg.header.frame_id = global_frame_id_;
  odom_msg.child_frame_id = base_frame_id_;
  
  // 设置位置和姿态（与pcl_pose相同）
  odom_msg.pose.pose = corrent_pose_with_cov_stamped_ptr_->pose.pose;
  
  // 可以设置协方差（根据实际需求调整）
  // 位置协方差（6x6矩阵，按行展开）
  std::array<double, 36> pose_covariance = {0.1, 0, 0, 0, 0, 0,
                                           0, 0.1, 0, 0, 0, 0,
                                           0, 0, 0.1, 0, 0, 0,
                                           0, 0, 0, 0.1, 0, 0,
                                           0, 0, 0, 0, 0.1, 0,
                                           0, 0, 0, 0, 0, 0.1};
  odom_msg.pose.covariance = pose_covariance;
  
  // 速度信息（如果没有速度数据可以设置为0）
  odom_msg.twist.twist.linear.x = 0.0;
  odom_msg.twist.twist.linear.y = 0.0;
  odom_msg.twist.twist.linear.z = 0.0;
  odom_msg.twist.twist.angular.x = 0.0;
  odom_msg.twist.twist.angular.y = 0.0;
  odom_msg.twist.twist.angular.z = 0.0;
  
  // 速度协方差
  std::array<double, 36> twist_covariance = {0.1, 0, 0, 0, 0, 0,
                                            0, 0.1, 0, 0, 0, 0,
                                            0, 0, 0.1, 0, 0, 0,
                                            0, 0, 0, 0.1, 0, 0,
                                            0, 0, 0, 0, 0.1, 0,
                                            0, 0, 0, 0, 0, 0.1};
  odom_msg.twist.covariance = twist_covariance;
  
  odom_pub_->publish(odom_msg);
//-------------------------------------
  

  geometry_msgs::msg::TransformStamped transform_stamped;
  transform_stamped.header.stamp = msg->header.stamp;
  transform_stamped.header.frame_id = global_frame_id_;
  transform_stamped.child_frame_id = base_frame_id_;
  transform_stamped.transform.translation.x = static_cast<double>(final_transformation(0, 3));
  transform_stamped.transform.translation.y = static_cast<double>(final_transformation(1, 3));
  transform_stamped.transform.translation.z = static_cast<double>(final_transformation(2, 3));
  transform_stamped.transform.rotation = quat_msg;
  broadcaster_.sendTransform(transform_stamped);

  geometry_msgs::msg::PoseStamped::SharedPtr pose_stamped_ptr(new geometry_msgs::msg::PoseStamped);
  pose_stamped_ptr->header.stamp = msg->header.stamp;
  pose_stamped_ptr->header.frame_id = global_frame_id_;
  pose_stamped_ptr->pose = corrent_pose_with_cov_stamped_ptr_->pose.pose;
  path_ptr_->poses.push_back(*pose_stamped_ptr);
  path_pub_->publish(*path_ptr_);

  last_scan_ptr_ = msg;

  if (enable_debug_) {
    std::cout << "滤波后的点云数量: " << filtered_cloud_ptr->size() << std::endl;
    std::cout << "配准耗时:" << time_align_end.seconds() - time_align_start.seconds() <<
      "[秒]" << std::endl;
    std::cout << "是否收敛: " << has_converged << std::endl;
    std::cout << "匹配得分: " << fitness_score << std::endl;
    std::cout << "最终变换矩阵:" << std::endl;
    std::cout << final_transformation << std::endl;
    /* 角度差检查
     * 旋转矩阵的迹 = 2(cos(theta) + 1)
     */
    double init_cos_angle = 0.5 *
      (init_guess.coeff(0, 0) + init_guess.coeff(1, 1) + init_guess.coeff(2, 2) - 1);
    double cos_angle = 0.5 *
      (final_transformation.coeff(0,
      0) + final_transformation.coeff(1, 1) + final_transformation.coeff(2, 2) - 1);
    double init_angle = acos(init_cos_angle);
    double angle = acos(cos_angle);
    // 参考:https://twitter.com/Atsushi_twi/status/1185868416864808960
    double delta_angle = abs(atan2(sin(init_angle - angle), cos(init_angle - angle)));
    std::cout << "角度差:" << delta_angle * 180 / M_PI << "[度]" << std::endl;
    std::cout << "-----------------------------------------------------" << std::endl;
  }
}
