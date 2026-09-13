#ifndef VEHICLE_NAVIGATION_HPP
#define VEHICLE_NAVIGATION_HPP

#include <memory>
#include <vector>
#include <cmath>
#include <string>

#include "rclcpp/rclcpp.hpp"
#include "std_msgs/msg/float32.hpp"
#include "std_msgs/msg/u_int32.hpp"
#include "std_msgs/msg/u_int8.hpp"
#include "std_msgs/msg/float64.hpp"
#include "std_msgs/msg/float32_multi_array.hpp"
#include "std_msgs/msg/float64_multi_array.hpp"
#include "std_msgs/msg/u_int8_multi_array.hpp"
#include "std_msgs/msg/int16_multi_array.hpp"
#include "std_msgs/msg/u_int32_multi_array.hpp"
#include "std_msgs/msg/string.hpp"
#include "nav_msgs/msg/odometry.hpp"
#include "geometry_msgs/msg/pose_stamped.hpp"
#include "geometry_msgs/msg/twist.hpp"  
#include "geometry_msgs/msg/pose_with_covariance_stamped.hpp"

#include "tf2_geometry_msgs/tf2_geometry_msgs.hpp"
#include "tf2/LinearMath/Matrix3x3.h"
#include "tf2/LinearMath/Quaternion.h"
#include "hardware_bind_lib/hardware_binder.hpp"
using namespace std;
using namespace std::chrono_literals;
using std::placeholders::_1;

class TrackedVehicleNavigation : public rclcpp::Node {
public:
    TrackedVehicleNavigation();
    
private:
    // 结构体定义
    struct Point {
        double x;
        double y;
    };

    struct Resultspoint {
        uint8_t NumResu;
        Point res1;
        Point res2;
    };

    struct VehicleControl {
        float Turning_angle;
        float angular_velocity;
        float linear_velocity;
        bool run_finish;
        bool spin_finish;
    };

    struct Posture {
        Point pos;
        float yaw;
    };

    enum obstacle_avoidance_mode {
        default_path = 0,
        left_path = 1,
        right_path = 2,
        parking = 3
    };
    
    // 定义状态枚举类型
    enum RunStatus {
        Preparing = 0,  // 正在准备
        Ready = 1,      // 就绪（可根据需要添加）
        LinearRunning = 2,    // 正在直线运行
        SpinRunning = 3,    // 正在自转运行
        Paused = 4,     // 暂停中
        Finished = 5,   // 运行完成
        PathReceived = 6, // 收到路径
        Canceled = 7,    // 取消完成
        Warning = 8      //警报
    };

    // 参数声明
    void declare_parameters();
    
    // 初始化发布者和订阅者
    void initialize_publishers();
    void initialize_subscribers();
    
    // 回调函数
    void timer_callback();
    void vehicle_ctrl_callback();
    void PoseStampedCallback(const geometry_msgs::msg::PoseStamped::SharedPtr laserOdometry);
    //void PoseWithCovarianceStampedCallbackCallback(const geometry_msgs::msg::PoseWithCovarianceStamped::SharedPtr laserOdometry);
    
    void PathPointCallback(const std_msgs::msg::Float64MultiArray::SharedPtr paraMsg);
    void PathPointLeftCallback(const std_msgs::msg::Float64MultiArray::SharedPtr paraMsg);
    void PathPointRightCallback(const std_msgs::msg::Float64MultiArray::SharedPtr paraMsg);
    void VchicleRunStarCallback(const std_msgs::msg::UInt8::SharedPtr paraMsg);
    void CloseRouteCallback(const std_msgs::msg::UInt8::SharedPtr paraMsg);
    void ObstacleAvoidanceCallback(const std_msgs::msg::UInt8::SharedPtr paraMsg);
    void VchicleSpinCallback(const std_msgs::msg::Float32::SharedPtr paraMsg);
    
    
    void cmdVelOutput(float linear_velocity, float angular_velocity);
    
    // 辅助函数
    void update_parameters();
    float LIMIT(float min_data, float actual_data, float max_data);
    float VectorAngle(Point v1, Point v2);
   
    double distance(const Point& p1, const Point& p2);
    double dotProduct(Point v1, Point v2);
    double crossProduct(Point v1, Point v2);
    double vectorLength(Point v);
    Point calculateP(Point A, Point B, Point C);
    Point calculateQ(Point P, Point B, double L);
    VehicleControl PurePursuitController(const Point& starP, const Point& endP, const Posture& curP, float lookahead_dist,
                                        float wheelbase, float speed, float xy_goal_tolerance, float angular_velocity_limit);
    VehicleControl SpinController(const Point& yaw_goal_vector, const Point& actual_angle_vector, float proportion, 
                                 float yaw_goal_tolerance, float angular_velocity_limit);                        
    float speedctrl( float deceleration, float speed , float path_distance , float min_speed);
    bool ObstacleAvoidancePathSelect();
    
    // 成员变量
    rclcpp::TimerBase::SharedPtr timer_;
    rclcpp::TimerBase::SharedPtr vehicle_ctrl_timer_;
    rclcpp::Time last_time_;
    
    // 发布者
    rclcpp::Publisher<std_msgs::msg::UInt32>::SharedPtr pubPathTrackingStatus;
    rclcpp::Publisher<std_msgs::msg::UInt8>::SharedPtr pubGoalFinish;
    rclcpp::Publisher<geometry_msgs::msg::Twist>::SharedPtr pubCmdVel;
    rclcpp::Publisher<std_msgs::msg::UInt8>::SharedPtr pubLocationMode;
    rclcpp::Publisher<std_msgs::msg::UInt8>::SharedPtr pubPathReceivedFinish;
    rclcpp::Publisher<std_msgs::msg::UInt8>::SharedPtr pubCloseRouteFinish;
    rclcpp::Publisher<std_msgs::msg::UInt8>::SharedPtr pubWarning;
    rclcpp::Publisher<std_msgs::msg::UInt8>::SharedPtr pubRunStatus;
    
    // 订阅者
    rclcpp::Subscription<geometry_msgs::msg::PoseStamped>::SharedPtr subPoseStamped;
    rclcpp::Subscription<std_msgs::msg::Float64MultiArray>::SharedPtr subPathPoint;
    rclcpp::Subscription<std_msgs::msg::Float64MultiArray>::SharedPtr subPathPointLeft;
    rclcpp::Subscription<std_msgs::msg::Float64MultiArray>::SharedPtr subPathPointRight;
    rclcpp::Subscription<std_msgs::msg::UInt8>::SharedPtr subCloseRoute;
    rclcpp::Subscription<std_msgs::msg::UInt8>::SharedPtr subObstacleAvoidance;
    
    rclcpp::Subscription<std_msgs::msg::UInt8>::SharedPtr subVehicleRunStar;
    rclcpp::Subscription<std_msgs::msg::Float32>::SharedPtr subVehicleSpin;
     
    // 其他成员变量
    float _lookahead_dist = 0;
    float _max_velocity = 0;
    float _min_velocity = 0;
    float _xy_goal_tolerance = 0;
    float _xy_middle_tolerance = 0;
    float _yaw_goal_tolerance = 0;
    float _wheel_base = 0;
    float _angular_velocity_limit = 0;
    float _proportion = 0;
    //定位中心->导航中心
    float _pose_offset_x_ = 0;
    float _pose_offset_y_ = 0;
    
    std_msgs::msg::Float64MultiArray _rev_path_point;
    std_msgs::msg::Float64MultiArray _rev_path_point_left;
    std_msgs::msg::Float64MultiArray _rev_path_point_right;
    std_msgs::msg::UInt8 _location_mode;
    std_msgs::msg::UInt8 _vehicle_run_status;
    uint32_t _path_point_number = 0;
    uint32_t _path_point_number_left = 0;
    uint32_t _path_point_number_right = 0;
    uint32_t _path_point_count  = 0;
    bool _Received_path = false;
    bool _Received_path_left = false;
    bool _Received_path_right = false;

    
    uint8_t _run_vehicle = 0;
    uint8_t _close_route = 0;
    uint8_t _obstacle_avoidance = 0;
    float _goal_spin_yaw = 0;
    bool _spin_vehicle_flag = false;
    bool _run_mode_switch = false;
    
    double _path_point[1000][9] = {0};
    double _path_point_left[1000][9] = {0};
    double _path_point_right[1000][9] = {0};
    Point _starA;
    Point _endB;
    Posture _curC;
    VehicleControl Ctrldata;
    uint16_t _pose_up_count = 0;
    float _init_point_speed = 0;
    float _run_mode = 0;
    //float _point_speed = 0;
    
    // 最新接收到的数据
    geometry_msgs::msg::PoseStamped::SharedPtr vehicle_pose_;
    geometry_msgs::msg::PoseStamped::SharedPtr last_vehicle_pose_;
    
    std::shared_ptr<hardware_bind_lib::HardwareBinder> hardware_binder_;

    
};

#endif // TRACKED_VEHICLE_NAVIGATION_HPP
