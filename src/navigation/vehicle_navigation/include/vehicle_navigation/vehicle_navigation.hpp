#ifndef VEHICLE_NAVIGATION__VEHICLE_NAVIGATION_HPP_
#define VEHICLE_NAVIGATION__VEHICLE_NAVIGATION_HPP_

#include <rclcpp/rclcpp.hpp>

#include <geometry_msgs/msg/pose_stamped.hpp>
#include <geometry_msgs/msg/twist.hpp>
#include <std_msgs/msg/float32.hpp>
#include <std_msgs/msg/float32_multi_array.hpp>
#include <std_msgs/msg/float64_multi_array.hpp>
#include <std_msgs/msg/string.hpp>
#include <std_msgs/msg/u_int8.hpp>
#include <std_msgs/msg/u_int8_multi_array.hpp>
#include <std_msgs/msg/u_int32.hpp>

#include <tf2/LinearMath/Matrix3x3.h>
#include <tf2/LinearMath/Quaternion.h>
#include <tf2_geometry_msgs/tf2_geometry_msgs.hpp>

#include <chrono>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <functional>
#include <memory>
#include <string>

// 保留原 cpp 中使用的 1000ms / 50ms 和 _1 写法。
using namespace std::chrono_literals;
using std::placeholders::_1;

// 兼容不同工程中 hardware_bind_lib 可能使用的头文件命名。
#if __has_include(<hardware_bind_lib/hardware_binder.hpp>)
  #include <hardware_bind_lib/hardware_binder.hpp>
#elif __has_include(<hardware_bind_lib/hardware_bind_lib.hpp>)
  #include <hardware_bind_lib/hardware_bind_lib.hpp>
#elif __has_include(<hardware_binder.hpp>)
  #include <hardware_binder.hpp>
#elif __has_include(<hardware_bind_lib.hpp>)
  #include <hardware_bind_lib.hpp>
#elif __has_include("hardware_bind_lib/hardware_binder.hpp")
  #include "hardware_bind_lib/hardware_binder.hpp"
#elif __has_include("hardware_bind_lib/hardware_bind_lib.hpp")
  #include "hardware_bind_lib/hardware_bind_lib.hpp"
#elif __has_include("hardware_binder.hpp")
  #include "hardware_binder.hpp"
#elif __has_include("hardware_bind_lib.hpp")
  #include "hardware_bind_lib.hpp"
#else
  #error "Cannot find hardware_bind_lib header. Please replace this include block with your original HardwareBinder header include."
#endif

class TrackedVehicleNavigation : public rclcpp::Node {
public:
    TrackedVehicleNavigation();

private:
    static constexpr std::size_t MAX_PATH_POINTS = 1000;
    static constexpr std::size_t PATH_POINT_FIELDS = 9;

    enum RunStatus : uint8_t {
        Ready = 0,
        LinearRunning = 1,
        SpinRunning = 2,
        Finished = 3,
        Warning = 4,
        Paused = 5,
        Canceled = 6
    };

    enum obstacle_avoidance_mode : uint8_t {
        default_path = 0,
        left_path = 1,
        right_path = 2,
        parking = 3
    };

    struct Point {
        double x = 0.0;
        double y = 0.0;
    };

    struct Posture {
        Point pos;
        double yaw = 0.0;
    };

    struct VehicleControl {
        float linear_velocity = 0.0F;
        float angular_velocity = 0.0F;
        float Turning_angle = 0.0F;
        bool run_finish = false;
        bool spin_finish = false;
    };

    // 初始化与定时器
    void declare_parameters();
    void initialize_publishers();
    void initialize_subscribers();
    void timer_callback();
    void vehicle_ctrl_callback();
    void update_parameters();

    // 基础控制与几何函数
    void cmdVelOutput(float linear_velocity, float angular_velocity);
    float LIMIT(float min_data, float actual_data, float max_data);
    float VectorAngle(Point v1, Point v2);
    double distance(const Point& p1, const Point& p2);
    double dotProduct(Point v1, Point v2);
    double crossProduct(Point v1, Point v2);
    double vectorLength(Point v);
    Point calculateP(Point A, Point B, Point C);
    Point calculateQ(Point P, Point B, double L);
    VehicleControl PurePursuitController(const Point& starP, const Point& endP, const Posture& curP,
                                         float lookahead_dist, float wheelbase, float speed,
                                         float xy_goal_tolerance, float angular_velocity_limit);
    VehicleControl SpinController(const Point& yaw_goal_vector, const Point& actual_angle_vector,
                                  float proportion, float yaw_goal_tolerance, float angular_velocity_limit);
    float speedctrl(float deceleration, float speed, float path_distance, float min_speed);
    bool ObstacleAvoidancePathSelect();

    // ROS 回调
    void PoseStampedCallback(const geometry_msgs::msg::PoseStamped::SharedPtr vehiclePose);
    void PathPointCallback(const std_msgs::msg::Float64MultiArray::SharedPtr paraMsg);
    void PathPointLeftCallback(const std_msgs::msg::Float64MultiArray::SharedPtr paraMsg);
    void PathPointRightCallback(const std_msgs::msg::Float64MultiArray::SharedPtr paraMsg);
    void VchicleRunStarCallback(const std_msgs::msg::UInt8::SharedPtr paraMsg);
    void ObstacleAvoidanceCallback(const std_msgs::msg::UInt8::SharedPtr paraMsg);
    void CloseRouteCallback(const std_msgs::msg::UInt8::SharedPtr paraMsg);
    void VchicleSpinCallback(const std_msgs::msg::Float32::SharedPtr paraMsg);

    // 新增：电气柜通信与路径点门动作处理
    void ElectricBinStateCallback(const std_msgs::msg::UInt8MultiArray::SharedPtr msg);
    int GetElectricBinActionByIndex(std::size_t path_point_index);
    bool HandleElectricBinAction(std::size_t path_point_index);
    void ResetElectricBinState();

    // 新增：机械臂交互（仿照原 ROS 1 TrackedVehicleNavigation 与 web_ctrl_robot 的握手逻辑）
    void ArmTaskDoneCallback(const std_msgs::msg::UInt32::SharedPtr paraMsg);
    bool HandleArmTask(std::size_t path_point_index, bool is_final);
    bool HandleArmWait();
    double GetArmTaskDurationByIndex(std::size_t path_point_index);
    void ResetArmTaskState();

    // 硬件授权
    std::shared_ptr<hardware_bind_lib::HardwareBinder> hardware_binder_;

    // 发布器
    rclcpp::Publisher<std_msgs::msg::UInt32>::SharedPtr pubPathTrackingStatus;
    rclcpp::Publisher<std_msgs::msg::UInt8>::SharedPtr pubGoalFinish;
    rclcpp::Publisher<geometry_msgs::msg::Twist>::SharedPtr pubCmdVel;
    rclcpp::Publisher<std_msgs::msg::UInt8>::SharedPtr pubLocationMode;
    rclcpp::Publisher<std_msgs::msg::UInt8>::SharedPtr pubPathReceivedFinish;
    rclcpp::Publisher<std_msgs::msg::UInt8>::SharedPtr pubCloseRouteFinish;
    rclcpp::Publisher<std_msgs::msg::UInt8>::SharedPtr pubWarning;
    rclcpp::Publisher<std_msgs::msg::UInt8>::SharedPtr pubRunStatus;
    rclcpp::Publisher<std_msgs::msg::String>::SharedPtr pubElectricBinDoorCmd;
    rclcpp::Publisher<std_msgs::msg::Float32MultiArray>::SharedPtr pubArmTask;

    // 订阅器
    rclcpp::Subscription<geometry_msgs::msg::PoseStamped>::SharedPtr subPoseStamped;
    rclcpp::Subscription<std_msgs::msg::Float64MultiArray>::SharedPtr subPathPoint;
    rclcpp::Subscription<std_msgs::msg::Float64MultiArray>::SharedPtr subPathPointLeft;
    rclcpp::Subscription<std_msgs::msg::Float64MultiArray>::SharedPtr subPathPointRight;
    rclcpp::Subscription<std_msgs::msg::UInt8>::SharedPtr subCloseRoute;
    rclcpp::Subscription<std_msgs::msg::UInt8>::SharedPtr subObstacleAvoidance;
    rclcpp::Subscription<std_msgs::msg::UInt8>::SharedPtr subVehicleRunStar;
    rclcpp::Subscription<std_msgs::msg::Float32>::SharedPtr subVehicleSpin;
    rclcpp::Subscription<std_msgs::msg::UInt8MultiArray>::SharedPtr subElectricBinState;
    rclcpp::Subscription<std_msgs::msg::UInt32>::SharedPtr subArmDone;

    // 定时器
    rclcpp::TimerBase::SharedPtr timer_;
    rclcpp::TimerBase::SharedPtr vehicle_ctrl_timer_;

    // 位姿缓存
    geometry_msgs::msg::PoseStamped::SharedPtr vehicle_pose_ = nullptr;
    geometry_msgs::msg::PoseStamped::SharedPtr last_vehicle_pose_ = nullptr;

    // 控制参数
    float _lookahead_dist = 1.0F;
    float _max_velocity = 1.0F;
    float _min_velocity = 1.0F;
    float _xy_goal_tolerance = 1.0F;
    float _xy_middle_tolerance = 1.0F;
    float _yaw_goal_tolerance = 1.0F;
    float _wheel_base = 1.0F;
    float _angular_velocity_limit = 0.25F;
    float _min_angular_velocity = 0.05F;
    float _proportion = 1.0F;
    float _pose_offset_x_ = 0.0F;
    float _pose_offset_y_ = 0.0F;
    bool _arm_task_enable = false;        // 是否启用机械臂交互
    double _arm_wait_timeout_sec = 60.0;  // 等待机械臂完成超时告警时间（秒）

    // 路径与控制状态
    Posture _curC;
    VehicleControl Ctrldata;
    Point _starA;
    Point _endB;

    std_msgs::msg::Float64MultiArray _rev_path_point;
    std_msgs::msg::Float64MultiArray _rev_path_point_left;
    std_msgs::msg::Float64MultiArray _rev_path_point_right;
    std_msgs::msg::UInt8 _location_mode;
    std_msgs::msg::UInt8 _vehicle_run_status;

    double _path_point[MAX_PATH_POINTS][PATH_POINT_FIELDS] = {};
    double _path_point_left[MAX_PATH_POINTS][PATH_POINT_FIELDS] = {};
    double _path_point_right[MAX_PATH_POINTS][PATH_POINT_FIELDS] = {};

    std::size_t _path_point_number = 0;
    std::size_t _path_point_number_left = 0;
    std::size_t _path_point_number_right = 0;
    std::size_t _path_point_count = 0;

    bool _Received_path = false;
    bool _Received_path_left = false;
    bool _Received_path_right = false;
    bool _spin_vehicle_flag = false;
    bool _run_mode_switch = false;

    int _run_vehicle = 0;
    int _obstacle_avoidance = obstacle_avoidance_mode::default_path;
    int _close_route = 0;
    int _pose_up_count = 0;

    float _init_point_speed = 0.0F;
    float _run_mode = 0.0F;
    float _goal_spin_yaw = 0.0F;

    // 新增：电气柜状态。X1/X3 为 0 表示限位触发，timeout 为 true 表示动作超时。
    uint8_t electric_bin_x1_state_ = 1;
    uint8_t electric_bin_x3_state_ = 1;
    bool electric_bin_timeout_ = false;

    // 新增：电气柜动作记忆。
    // 0=无等待，1=停车等待开门完成，2=停车等待关门完成。
    int electric_bin_wait_action_ = 0;
    // -1=没有处理过任何路径点；其他值=已经处理过对应路径点的门动作。
    int electric_bin_handled_path_index_ = -1;

    // 新增：机械臂交互状态（对应原 ROS 1 版本里的 starcarend 停走门控与 secs22 作业计时）。
    bool arm_task_waiting_ = false;      // true=已发布任务，正在停车等待完成回执
    bool arm_task_done_ = false;         // true=已收到 /arm/done
    int arm_handled_path_index_ = -1;    // 已发布过任务的路径点序号，-1=无
    rclcpp::Time arm_wait_start_time_;   // 开始等待的时刻（用于作业时长与超时判断）
};

#endif  // VEHICLE_NAVIGATION__VEHICLE_NAVIGATION_HPP_
