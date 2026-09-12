#include "vehicle_navigation/vehicle_navigation.hpp"

TrackedVehicleNavigation::TrackedVehicleNavigation() : Node("tracked_vehicle_navigation") {
    // 参数声明与初始化
    declare_parameters();
    
    //创建硬件绑定器实例
    hardware_binder_ = std::make_shared<hardware_bind_lib::HardwareBinder>();

    //第三处，设置模式，true会输出硬件id,false不输出，最终代码设置为false
    hardware_binder_->setDebugMode(false);
    hardware_binder_->generateHardwareID();

    //第四处读取授权参数
    this->declare_parameter<std::string>("authorized_hardware_id", "");
    std::string authorized_id = this->get_parameter("authorized_hardware_id").as_string();
		
    //第五处执行硬件验证
    if (!hardware_binder_->verifyAuthorization(authorized_id)) {
        RCLCPP_FATAL(this->get_logger(), "Hardware verification failed. Shutting down...");
        rclcpp::shutdown();
	return;
    }
    
    // 初始化发布者和订阅者
    initialize_publishers();
    initialize_subscribers();
    
    // 初始化定时器
    timer_ = create_wall_timer(
        1000ms, std::bind(&TrackedVehicleNavigation::timer_callback, this));
        
    vehicle_ctrl_timer_ = create_wall_timer(
        50ms, std::bind(&TrackedVehicleNavigation::vehicle_ctrl_callback, this));
        
    _vehicle_run_status.data = RunStatus::Ready;
}

void TrackedVehicleNavigation::declare_parameters() {
    this->declare_parameter("lookahead_dist", 1.0);//前瞻距离
    this->declare_parameter("max_velocity", 1.0);//最大速度
    this->declare_parameter("min_velocity", 1.0);//最小速度
    this->declare_parameter("xy_goal_tolerance", 1.0);//xy目标点容差
    this->declare_parameter("xy_middle_tolerance", 1.0);//xy目标点容差
    this->declare_parameter("yaw_goal_tolerance", 1.0);//yaw目标点容差
    this->declare_parameter("wheel_base", 1.0);//轴距
    this->declare_parameter("angular_velocity_limit", 0.25);//角速度限制
    this->declare_parameter("min_angular_velocity", 0.05);//最小角速度
    this->declare_parameter("proportion", 1.0);//比例系数
    this->declare_parameter("pose_offset_x_", 0.0);//定位偏移x
    this->declare_parameter("pose_offset_y_", 0.0);//定位偏移y
    this->declare_parameter("arm_task_enable", false);//是否启用机械臂交互（仿照原 /PerformTasks 握手）
    this->declare_parameter("arm_wait_timeout_sec", 60.0);//等待机械臂完成超时告警时间（秒）
    
    // 获取参数值
    this->get_parameter("lookahead_dist", _lookahead_dist);
    this->get_parameter("max_velocity", _max_velocity);
    this->get_parameter("min_velocity", _min_velocity);
    this->get_parameter("xy_goal_tolerance", _xy_goal_tolerance);
    this->get_parameter("xy_middle_tolerance", _xy_middle_tolerance);
    this->get_parameter("yaw_goal_tolerance", _yaw_goal_tolerance);
    this->get_parameter("wheel_base", _wheel_base);
    this->get_parameter("angular_velocity_limit", _angular_velocity_limit);
    this->get_parameter("min_angular_velocity", _min_angular_velocity);
    this->get_parameter("proportion", _proportion);
    this->get_parameter("pose_offset_x_", _pose_offset_x_);
    this->get_parameter("pose_offset_y_", _pose_offset_y_);
    this->get_parameter("arm_task_enable", _arm_task_enable);
    this->get_parameter("arm_wait_timeout_sec", _arm_wait_timeout_sec);

}

void TrackedVehicleNavigation::initialize_publishers() {
    pubPathTrackingStatus = this->create_publisher<std_msgs::msg::UInt32>(
        "/path_point_id", 10);//DIAN BIAN HAO
    pubGoalFinish = this->create_publisher<std_msgs::msg::UInt8>(
        "/goal_finish", 10);
    pubCmdVel = this->create_publisher<geometry_msgs::msg::Twist>(
        "/cmd_vel", 10);
    pubLocationMode = this->create_publisher<std_msgs::msg::UInt8>(
        "/location_mode", 10);
    pubPathReceivedFinish = this->create_publisher<std_msgs::msg::UInt8>(
        "/path_received_finish", 10);
    pubCloseRouteFinish = this->create_publisher<std_msgs::msg::UInt8>(
        "/close_route_finish", 10);
    pubWarning = this->create_publisher<std_msgs::msg::UInt8>(
        "/vehicle_warning", 10);
    pubRunStatus = this->create_publisher<std_msgs::msg::UInt8>(
        "/vehicle_run_status", 10);

    // 新增：导航节点给电气柜节点发送开门/关门命令
    // pubElectricBinDoorCmd = this->create_publisher<std_msgs::msg::String>(
    //     "/car_to_bin/door_cmd", 10);

    // 新增：导航节点给机械臂代理发送任务请求（对应原 /PerformTasks）
    pubArmTask = this->create_publisher<std_msgs::msg::Float32MultiArray>(
        "/arm/task", 10);
    
}

void TrackedVehicleNavigation::initialize_subscribers() {
    subPoseStamped = this->create_subscription<geometry_msgs::msg::PoseStamped>(
        "tf_pose", 5, std::bind(&TrackedVehicleNavigation::PoseStampedCallback, this, _1));

    subPathPoint = this->create_subscription<std_msgs::msg::Float64MultiArray>(
        "path_point", 1, std::bind(&TrackedVehicleNavigation::PathPointCallback, this, _1));
    subPathPointLeft = this->create_subscription<std_msgs::msg::Float64MultiArray>(
        "path_point_left", 1, std::bind(&TrackedVehicleNavigation::PathPointLeftCallback, this, _1));
    subPathPointRight = this->create_subscription<std_msgs::msg::Float64MultiArray>(
        "path_point_right", 1, std::bind(&TrackedVehicleNavigation::PathPointRightCallback, this, _1));

    subCloseRoute = this->create_subscription<std_msgs::msg::UInt8>(
        "close_route", 5, std::bind(&TrackedVehicleNavigation::CloseRouteCallback, this, _1));
    subObstacleAvoidance = this->create_subscription<std_msgs::msg::UInt8>(
        "obstacle_avoidance", 1, std::bind(&TrackedVehicleNavigation::ObstacleAvoidanceCallback, this, _1));
    
    subVehicleRunStar = this->create_subscription<std_msgs::msg::UInt8>(
        "vehicle_run_star", 1, std::bind(&TrackedVehicleNavigation::VchicleRunStarCallback, this, _1));
    subVehicleSpin = this->create_subscription<std_msgs::msg::Float32>(
        "spin_action", 1, std::bind(&TrackedVehicleNavigation::VchicleSpinCallback, this, _1));

    // 新增：订阅电气柜综合状态：[x1,x2,x3,y1,y2,timeout]
    // subElectricBinState = this->create_subscription<std_msgs::msg::UInt8MultiArray>(
    //     "/bin_to_car/integrated_state", 10,
    //     std::bind(&TrackedVehicleNavigation::ElectricBinStateCallback, this, _1));

    // 新增：机械臂任务完成回执（对应原 /VehicleStartsRun，类型保持 UInt32 与机械臂代理一致）
    subArmDone = this->create_subscription<std_msgs::msg::UInt32>(
        "/arm/done", 1, std::bind(&TrackedVehicleNavigation::ArmTaskDoneCallback, this, _1));
}

void TrackedVehicleNavigation::timer_callback() {
    // 更新参数
    //update_parameters();
    
    //RCLCPP_INFO(this->get_logger(), 
    //    "lookahead_dist: %f max_velocity: %f min_velocity: %f _xy_goal_tolerance: %f yaw_goal_tolerance: %f wheel_base: %f angular_velocity_limit: %f proportion: %f ",
    //    _lookahead_dist, _max_velocity, _min_velocity,_xy_goal_tolerance , _yaw_goal_tolerance ,_wheel_base, _angular_velocity_limit, _proportion);
    
    
    pubRunStatus->publish(_vehicle_run_status);
}


void TrackedVehicleNavigation::vehicle_ctrl_callback() {
    
    //if(!_Received_path) return;
    std_msgs::msg::UInt8 _xy_yaw_goal_finish;
    std_msgs::msg::UInt32 _path_point_id;   

    // 新增：如果已经处于“停车等门”状态，先处理电气柜逻辑。
    // 放在位姿不更新判断前，避免车辆停住后因为位姿不变而无法继续检查 X1/X3。
    // if(electric_bin_wait_action_ != 0)
    // {
    //     if(HandleElectricBinAction(_path_point_count))
    //     {
    //         return;
    //     }
    // }

    // 新增：如果已经处于“停车等机械臂”状态，先处理完成回执。
    // 同样放在位姿不更新判断前，避免车辆停住后无法继续检查 /arm/done。
    if(arm_task_waiting_)
    {
        if(HandleArmWait())
        {
            return;
        }
    }
    
    if( _pose_up_count > 20)
    {
        cmdVelOutput(0.0, 0.0);
    }
     
    //数据不更新，直接结束
    if(vehicle_pose_ == last_vehicle_pose_ )
    {
        _pose_up_count ++;
        if(_pose_up_count > 100)//设置上限数值，防止溢出
        {
            _pose_up_count = 100;
        }
        return;
    }
    _pose_up_count = 0;
    last_vehicle_pose_ = vehicle_pose_;
    
    //获取x,y,yaw
    double roll, pitch, yaw;
    tf2::Quaternion quat;
    tf2::fromMsg(vehicle_pose_->pose.orientation, quat);
    tf2::Matrix3x3(quat).getRPY(roll, pitch, yaw);
        
    _curC.pos.x  = vehicle_pose_->pose.position.x  - ( ( _pose_offset_x_ * cos(yaw) ) - ( _pose_offset_y_ * sin(yaw) ) );
    _curC.pos.y  = vehicle_pose_->pose.position.y  - ( ( _pose_offset_x_ * sin(yaw) ) + ( _pose_offset_y_ * cos(yaw) ) );
    
    _curC.yaw = yaw; 
    //_curC.pos.x = vehicle_pose_->pose.position.x;
    //_curC.pos.y = vehicle_pose_->pose.position.y;
     
    //进入路径追踪
    if( _run_vehicle == 1 && _Received_path == true && _spin_vehicle_flag == false)
    {
        float heading = _curC.yaw * 180 / 3.1415926;
        
        RCLCPP_INFO(this->get_logger(), 
                   "[car xxx: %f] [car yyy: %f] [car heading: %f] ",
                   vehicle_pose_->pose.position.x, vehicle_pose_->pose.position.y, 
                   heading);
                   
        //选择路径
        bool _exists_path = ObstacleAvoidancePathSelect();
        if(_exists_path == false)
        {
            cmdVelOutput(0.0, 0.0);
            return;
        }

        float path_length = distance(_starA, _endB);
        float driving_distance = distance(_starA, _curC.pos);
        float _next_run_mode = _path_point[_path_point_count +1][5]; //下一个点的模式
        
        float path_length_remaining = path_length - driving_distance - _xy_middle_tolerance;
        
        //分情况处理点位xy的容错，默认为中间点的容错，最后点的容错单独处理
        float _xy_goal_tolerance_intput = _xy_middle_tolerance;
        if(_path_point_count >= _path_point_number-3)
        {
            _xy_goal_tolerance_intput = _xy_goal_tolerance;
            path_length_remaining = path_length - driving_distance -_xy_goal_tolerance;
        }
        

        float _point_speed = _init_point_speed;
        if (_next_run_mode == 1 || _path_point_count >= _path_point_number -2)
        {
            _point_speed = speedctrl(0.05, _init_point_speed, path_length_remaining, 0.2);
            
        }
        
        RCLCPP_INFO(this->get_logger(), 
                   "[car _run_mode: %f] [car _point_speed: %f] [ _xy_goal_tolerance_intput: %f]",
                   _run_mode,_point_speed,_xy_goal_tolerance_intput);
        
        if(_run_mode == 0) //转弯模式 
        {
            Ctrldata = PurePursuitController(_starA, _endB, _curC, _lookahead_dist, _wheel_base, _point_speed, _xy_goal_tolerance_intput, _angular_velocity_limit);
        }
        else if(_run_mode == 1)//自转模式
        {
            Point path_vector;  
            
            if(_point_speed > 0)
            {
                //正向行驶路径向量
                path_vector.x = _endB.x - _starA.x;
                path_vector.y = _endB.y - _starA.y;
            }
            else if(_point_speed < 0)
            {
                //正向行驶路径向量
                path_vector.x = _starA.x - _endB.x;
                path_vector.y = _starA.y - _endB.y;
            }
            
            
            RCLCPP_INFO(this->get_logger(), 
                   "[path_vector.x: %f], [path_vector.x: %f], [_run_mode_switch: %d]",
                   path_vector.x,path_vector.y, _run_mode_switch);

            Point vehicle_vector;  //车辆向量
            vehicle_vector.x = cos(_curC.yaw);
            vehicle_vector.y = sin(_curC.yaw); 

            if(_run_mode_switch == false)
            {
                Ctrldata = SpinController(path_vector, vehicle_vector, _proportion, _yaw_goal_tolerance, _angular_velocity_limit);
            }
            else if(_run_mode_switch == true)
            {
                Ctrldata = PurePursuitController(_starA, _endB, _curC, _lookahead_dist, _wheel_base, _point_speed, _xy_goal_tolerance, _angular_velocity_limit);
            }
            
            if(Ctrldata.spin_finish)  //自转完成切换为追踪模式
            {
                _run_mode_switch = true;
            }
            
            
        }
        
        if(Ctrldata.run_finish)  //路线切换
        {
            // 新增：到达下一个路径点时，先处理该点的电气柜动作属性。
            // 1/2 会停车等待门到位；3/4 只发命令不停车；0 不处理。
            std::size_t next_path_point_count = _path_point_count + 1;
            // if(HandleElectricBinAction(next_path_point_count))
            // {
            //     return;
            // }

            // 新增：到达下一个路径点时再处理机械臂任务（仿照原 TrackedVehicleNavigation 与 web_ctrl_robot）。
            // 中间点：发布 /arm/task 后停车等待 /arm/done；终点：只发任务帧、不等待。
            bool next_is_final = (_path_point_number > 0) &&
                                 (next_path_point_count >= _path_point_number - 1);
            if(HandleArmTask(next_path_point_count, next_is_final))
            {
                return;
            }

            _path_point_count ++;  //路段指针加一
            _run_mode_switch = false;  //追踪中模式切换，自转标志恢复
            
            //发布路段id
            _path_point_id.data = _path_point[_path_point_count][3];
            pubPathTrackingStatus->publish(_path_point_id);

            //发布定位模式
            _location_mode.data = _path_point[_path_point_count][6];
            pubLocationMode->publish(_location_mode);
            
            //RCLCPP_INFO(this->get_logger(), 
            //       "[car _path_point_count--------------------: %d] ",
            //       _path_point_count);
            
            if(_path_point_count >= _path_point_number-1)//判断是否到达终点
            {
                //判断终点是否自转，并启动
                float _goal_run_mode = _path_point[_path_point_count][5]; //起点,转弯模式0，自转模式1
                
                
                _Received_path = false; //清空接收路径标志
                _Received_path_right = false;//清空接收路径标志
                _Received_path_left = false;//清空接收路径标志
                _run_vehicle = 0;  //清空车辆启动命令
                _obstacle_avoidance = 0;
                //发布达到终点标志
                _xy_yaw_goal_finish.data = 1;
                pubGoalFinish->publish(_xy_yaw_goal_finish);
                
                //RCLCPP_INFO(this->get_logger(), 
                //   "[car _path_point_count--------------------: %d] , _goal_run_mode---------:%f",
                //   _path_point_count, _goal_run_mode);
                
                if(_goal_run_mode == 1)
                {
                    _goal_spin_yaw = _path_point[_path_point_count][2]; 
                    _spin_vehicle_flag = true;
                    _run_vehicle = 1;  
                }
                _path_point_count = 0; //清空路段指针
            }
        }
        
        cmdVelOutput(Ctrldata.linear_velocity, Ctrldata.angular_velocity);
        _vehicle_run_status.data = RunStatus::LinearRunning;
    
    }  //独立自转模式
    else if( _run_vehicle == 1 && _Received_path == false && _spin_vehicle_flag == true)
    {
        Point _goal_spin_vector; //目标角度
        _goal_spin_vector.x = cos(_goal_spin_yaw);
        _goal_spin_vector.y = sin(_goal_spin_yaw);

        Point _cur_spin_vector;  //车辆当前的角度
        _cur_spin_vector.x = cos(_curC.yaw);
        _cur_spin_vector.y = sin(_curC.yaw);

        //开始自转
        Ctrldata = SpinController(_goal_spin_vector, _cur_spin_vector, _proportion, _yaw_goal_tolerance, _angular_velocity_limit);
        
        if(Ctrldata.spin_finish) //自转完成
        {
            _spin_vehicle_flag = false;
            _xy_yaw_goal_finish.data = 2;
            pubGoalFinish->publish(_xy_yaw_goal_finish);
            _run_vehicle = 0;
        }
        
        cmdVelOutput(Ctrldata.linear_velocity, Ctrldata.angular_velocity);
        _vehicle_run_status.data = RunStatus::SpinRunning;
    }//停车模式
    else if(_Received_path == false /*|| _spin_vehicle_flag == false*/ || _run_vehicle == 0)
    {      
        cmdVelOutput(0.0, 0.0);
        if(_Received_path == false && _run_vehicle == 0)
        {
            _vehicle_run_status.data = RunStatus::Finished;
        }
        else  if(_Received_path == false && _run_vehicle == 1)
        {
            _vehicle_run_status.data = RunStatus::Warning;
        }
        else  if(_Received_path == true && _run_vehicle == 0)
        {
            _vehicle_run_status.data = RunStatus::Paused;
        }
    }
    
        RCLCPP_INFO(this->get_logger(), 
                   "[car _Received_path: %d] [car _spin_vehicle_flag: %d] [car _run_vehicle: %d] ",
                   _Received_path, _spin_vehicle_flag, _run_vehicle);
    
}

void TrackedVehicleNavigation::update_parameters() {
    this->get_parameter("lookahead_dist", _lookahead_dist);
    this->get_parameter("max_velocity", _max_velocity);
    this->get_parameter("min_velocity", _min_velocity);
    this->get_parameter("xy_goal_tolerance", _xy_goal_tolerance);
    this->get_parameter("yaw_goal_tolerance", _yaw_goal_tolerance);
    this->get_parameter("wheel_base", _wheel_base);
    this->get_parameter("angular_velocity_limit", _angular_velocity_limit);
    this->get_parameter("min_angular_velocity", _min_angular_velocity);
    this->get_parameter("proportion", _proportion);
    this->get_parameter("pose_offset_x_", _pose_offset_x_);
    this->get_parameter("pose_offset_y_", _pose_offset_y_);    
}
//发布速度
void TrackedVehicleNavigation::cmdVelOutput(float linear_velocity, float angular_velocity)
{
    auto twist_msg = geometry_msgs::msg::Twist();
    twist_msg.linear.x  = linear_velocity;  // 设置线速度
    twist_msg.angular.z = angular_velocity; // 设置角速度
    pubCmdVel->publish(twist_msg); 
}

//限制幅度
float TrackedVehicleNavigation::LIMIT(float min_data, float actual_data, float max_data) {
    float output_data = actual_data >= min_data ? actual_data : min_data;
    output_data = output_data <= max_data ? output_data : max_data;
    return output_data;
}

//向量夹角
float TrackedVehicleNavigation::VectorAngle(Point v1, Point v2) {
    float dot_vector = dotProduct(v1 ,v2);
    float cro_vector = crossProduct(v1 ,v2);
    float vector_angle = atan2(cro_vector, dot_vector);
    return vector_angle;
}

//两点之间距离
double TrackedVehicleNavigation::distance(const Point& p1, const Point& p2) {
    return std::sqrt(std::pow(p2.x - p1.x, 2) + std::pow(p2.y - p1.y, 2));
}

//点乘
double TrackedVehicleNavigation::dotProduct(Point v1, Point v2) {
    return v1.x * v2.x + v1.y * v2.y;
}

//叉乘
double TrackedVehicleNavigation::crossProduct(Point v1, Point v2) {
    return v1.x * v2.y - v2.x * v1.y;
}

//向量长度
double TrackedVehicleNavigation::vectorLength(Point v) {
    return std::sqrt(v.x * v.x + v.y * v.y);
}

//C在AB线上的垂足P
TrackedVehicleNavigation::Point TrackedVehicleNavigation::calculateP(Point A, Point B, Point C) {
    Point AB, AC, AP;
    AB.x = B.x - A.x;
    AB.y = B.y - A.y;
    AC.x = C.x - A.x;
    AC.y = C.y - A.y;
    double AB_length = vectorLength(AB);
    double projectionFactor = dotProduct(AB, AC) / (AB_length * AB_length);
    AP.x = A.x + AB.x * projectionFactor;
    AP.y = A.y + AB.y * projectionFactor;
    return AP;
}

//车辆追踪点Q
TrackedVehicleNavigation::Point TrackedVehicleNavigation::calculateQ(Point P, Point B, double L) {
    Point PB;
    PB.x = B.x - P.x;
    PB.y = B.y - P.y;
    double PB_length = vectorLength(PB);
    double factor = L / PB_length;
    Point AQ;
    AQ.x = P.x + PB.x * factor;
    AQ.y = P.y + PB.y * factor;
    return AQ;
}
//追踪控制
TrackedVehicleNavigation::VehicleControl TrackedVehicleNavigation::PurePursuitController(
    const Point& starP, const Point& endP, const Posture& curP, float lookahead_dist,
    float wheelbase, float speed, float xy_goal_tolerance, float angular_velocity_limit) {
  
    
    VehicleControl _ctrl_data;
    
    //垂足PP
    Point PP = calculateP(starP, endP, curP.pos);
    //预瞄点QQ
    Point QQ = calculateQ(PP, endP, lookahead_dist);

    Point _vehicle_vector;//车辆向量
    _vehicle_vector.x = cos(curP.yaw);
    _vehicle_vector.y = sin(curP.yaw);
    
    Point _preview_vector;//预瞄向量
    _preview_vector.x = QQ.x - curP.pos.x;
    _preview_vector.y = QQ.y - curP.pos.y;
    
    Point _path_vector_AB;//路径向量
    _path_vector_AB.x = endP.x - starP.x;
    _path_vector_AB.y = endP.y - starP.y;

    Point _path_vector_AC; //路径向量
    _path_vector_AC.x = curP.pos.x - starP.x;
    _path_vector_AC.y = curP.pos.y - starP.y;
    
    //预瞄向量和车辆夹角
    float _Pre_vector_and_vehicle_angle = VectorAngle(_vehicle_vector, _preview_vector);
    float _dot_path_and_vehicle_angle = dotProduct(_path_vector_AB, _path_vector_AC) ;
    
    _ctrl_data.angular_velocity = LIMIT(-angular_velocity_limit, 2 * speed * sin(_Pre_vector_and_vehicle_angle) / lookahead_dist, angular_velocity_limit);
    _ctrl_data.Turning_angle    = atan(2 * wheelbase * sin(_Pre_vector_and_vehicle_angle) / lookahead_dist);
    _ctrl_data.linear_velocity  = speed;

    float path_length = distance(starP, endP);
    float driving_distance = distance(curP.pos, starP);
    
    _ctrl_data.spin_finish = false;
    
    //车辆到达终点判断条件，AB<AC&&AB和AC夹角小于90度
    if(driving_distance > path_length - xy_goal_tolerance && _dot_path_and_vehicle_angle > 0) {    
        _ctrl_data.run_finish = true;
    }
    else {
        _ctrl_data.run_finish = false;
    }

    return _ctrl_data;
}

//自转控制
TrackedVehicleNavigation::VehicleControl TrackedVehicleNavigation::SpinController(const Point& yaw_goal_vector, const Point& actual_angle_vector,float proportion, 
                                                                                  float yaw_goal_tolerance, float angular_velocity_limit) {
    VehicleControl _ctrl_data;
    //预瞄向量和车辆夹角
    float _goal_vector_and_vehicle_rad = VectorAngle(actual_angle_vector, yaw_goal_vector);
    float _goal_vector_and_vehicle_angle = _goal_vector_and_vehicle_rad * 180 / 3.1415926;
    RCLCPP_INFO(this->get_logger(), "_goal_vector_and_vehicle_angle----------: %f", _goal_vector_and_vehicle_angle);
    
    //float temp_angular_velocity = _goal_vector_and_vehicle_angle ;
    //RCLCPP_INFO(this->get_logger(), "temp_angular_velocity----------: %f", temp_angular_velocity);
    
    float temp_angular_velocity_rad = _goal_vector_and_vehicle_angle * proportion * 3.14 / 180.0;
    
    // 先限制最大值
    float angular_velocity = LIMIT(-angular_velocity_limit, temp_angular_velocity_rad, angular_velocity_limit);
    
    // 添加最小角速度限制（防止角度差小时自转过慢）
    if (angular_velocity > 0 && angular_velocity < _min_angular_velocity) {
        angular_velocity = _min_angular_velocity;
    } else if (angular_velocity < 0 && angular_velocity > -_min_angular_velocity) {
        angular_velocity = -_min_angular_velocity;
    }
    
    _ctrl_data.angular_velocity = angular_velocity;
    _ctrl_data.Turning_angle    = 0; 
    _ctrl_data.linear_velocity  = 0;
    _ctrl_data.run_finish = false;

    if(abs(_goal_vector_and_vehicle_angle) < yaw_goal_tolerance) {
        _ctrl_data.spin_finish = true;
    }
    else {
        _ctrl_data.spin_finish = false; 
    }
    
    return _ctrl_data;
}

//速度控制
float TrackedVehicleNavigation::speedctrl( float deceleration, float speed , float path_distance , float min_speed) {	
    float deceleration_distance = speed * speed / (2 * deceleration);
    float speed_output = 0;
    if (deceleration_distance < path_distance)
    {
        speed_output = speed;
    }else{
        if(path_distance < 0) path_distance=0.001;
        speed_output = sqrt(2 * deceleration * path_distance);
        RCLCPP_INFO(this->get_logger(), 
                   "[speed_output: %f], [min_speed: %f]",
                   speed_output,min_speed);
        if (speed_output <= min_speed)
        {
            speed_output = min_speed;
        }
        if (speed < 0) //倒车
        {
            speed_output = 0 - speed_output;
        }
    }
    
    return speed_output;
}

// 主要回调函数实现，获取位姿信息
void TrackedVehicleNavigation::PoseStampedCallback(const geometry_msgs::msg::PoseStamped::SharedPtr vehiclePose) {
    if (vehiclePose != nullptr)
    {
        vehicle_pose_ = vehiclePose;
    }
}

// 电气柜状态回调：电气柜节点固定发布 [x1,x2,x3,y1,y2,timeout]
// void TrackedVehicleNavigation::ElectricBinStateCallback(const std_msgs::msg::UInt8MultiArray::SharedPtr msg)
// {
//     // 导航只需要 X1、X3 和 timeout。
//     electric_bin_x1_state_ = msg->data[0];       // X1：舱门全开到位，0=触发
//     electric_bin_x3_state_ = msg->data[2];       // X3：舱门全关到位，0=触发
//     electric_bin_timeout_  = (msg->data[5] != 0);// timeout：1=动作超时
// }

// 读取默认路径中某个路径点的门动作属性。
// path_point 每个点有 9 个字段，C++ 下标 [8] 表示第 9 个字段。
// int TrackedVehicleNavigation::GetElectricBinActionByIndex(std::size_t path_point_index)
// {
//     return static_cast<int>(_path_point[path_point_index][8]);
// }

// 取消路线时，清空本次新增的电气柜动作记忆。
// void TrackedVehicleNavigation::ResetElectricBinState()
// {
//     electric_bin_wait_action_ = 0;
//     electric_bin_handled_path_index_ = -1;
// }

// 电气柜动作处理函数。
// 返回 true：当前周期需要停车等待，后面的原导航控制不要继续执行。
// 返回 false：电气柜不拦截，原导航控制继续执行。
// bool TrackedVehicleNavigation::HandleElectricBinAction(std::size_t path_point_index)
// {
//     // 正在停车等开门：一直停车，直到 X1=0。
//     if(electric_bin_wait_action_ == 1)
//     {
//         cmdVelOutput(0.0, 0.0);
//         _vehicle_run_status.data = RunStatus::Paused;
//
//         if(electric_bin_timeout_)
//         {
//             RCLCPP_ERROR_THROTTLE(this->get_logger(), *this->get_clock(), 1000,
//                                   "【电气柜】开门动作超时，导航保持停车");
//             return true;
//         }
//
//         if(electric_bin_x1_state_ == 0)
//         {
//             RCLCPP_WARN(this->get_logger(), "【电气柜】开门到位 X1=0，恢复原导航控制");
//             electric_bin_wait_action_ = 0;
//             return false;
//         }
//
//         return true;
//     }
//
//     // 正在停车等关门：一直停车，直到 X3=0。
//     if(electric_bin_wait_action_ == 2)
//     {
//         cmdVelOutput(0.0, 0.0);
//         _vehicle_run_status.data = RunStatus::Paused;
//
//         if(electric_bin_timeout_)
//         {
//             RCLCPP_ERROR_THROTTLE(this->get_logger(), *this->get_clock(), 1000,
//                                   "【电气柜】关门动作超时，导航保持停车");
//             return true;
//         }
//
//         if(electric_bin_x3_state_ == 0)
//         {
//             RCLCPP_WARN(this->get_logger(), "【电气柜】关门到位 X3=0，恢复原导航控制");
//             electric_bin_wait_action_ = 0;
//             return false;
//         }
//
//         return true;
//     }
//
//     // 当前路径点已经处理过门动作，不重复发送 open_door / close_door。
//     if(static_cast<int>(path_point_index) == electric_bin_handled_path_index_)
//     {
//         return false;
//     }
//
//     int action = GetElectricBinActionByIndex(path_point_index);
//     if(action == 0)
//     {
//         return false;
//     }
//
//     electric_bin_handled_path_index_ = static_cast<int>(path_point_index);
//
//     // 1：停车开门。若 X1 已经到位，就不需要再停车等待。
//     if(action == 1)
//     {
//         if(!electric_bin_timeout_ && electric_bin_x1_state_ == 0)
//         {
//             RCLCPP_WARN(this->get_logger(), "【电气柜】开门已到位 X1=0，继续行驶");
//             return false;
//         }
//
//         std_msgs::msg::String cmd_msg;
//         cmd_msg.data = "open_door";
//         pubElectricBinDoorCmd->publish(cmd_msg);
//
//         electric_bin_wait_action_ = 1;
//         cmdVelOutput(0.0, 0.0);
//         _vehicle_run_status.data = RunStatus::Paused;
//         RCLCPP_WARN(this->get_logger(), "【电气柜】停车开门：point_index=%zu，等待 X1=0", path_point_index);
//         return true;
//     }
//
//     // 2：停车关门。若 X3 已经到位，就不需要再停车等待。
//     if(action == 2)
//     {
//         if(!electric_bin_timeout_ && electric_bin_x3_state_ == 0)
//         {
//             RCLCPP_WARN(this->get_logger(), "【电气柜】关门已到位 X3=0，继续行驶");
//             return false;
//         }
//
//         std_msgs::msg::String cmd_msg;
//         cmd_msg.data = "close_door";
//         pubElectricBinDoorCmd->publish(cmd_msg);
//
//         electric_bin_wait_action_ = 2;
//         cmdVelOutput(0.0, 0.0);
//         _vehicle_run_status.data = RunStatus::Paused;
//         RCLCPP_WARN(this->get_logger(), "【电气柜】停车关门：point_index=%zu，等待 X3=0", path_point_index);
//         return true;
//     }
//
//     // 3：不停车开门，只发一次命令，原导航继续。
//     if(action == 3)
//     {
//         std_msgs::msg::String cmd_msg;
//         cmd_msg.data = "open_door";
//         pubElectricBinDoorCmd->publish(cmd_msg);
//         RCLCPP_WARN(this->get_logger(), "【电气柜】不停车开门：point_index=%zu", path_point_index);
//         return false;
//     }
//
//     // 4：不停车关门，只发一次命令，原导航继续。
//     if(action == 4)
//     {
//         std_msgs::msg::String cmd_msg;
//         cmd_msg.data = "close_door";
//         pubElectricBinDoorCmd->publish(cmd_msg);
//         RCLCPP_WARN(this->get_logger(), "【电气柜】不停车关门：point_index=%zu", path_point_index);
//         return false;
//     }
//
//     return false;
// }

// 机械臂交互处理（仿照原 ROS 1 TrackedVehicleNavigation 与 web_ctrl_robot 的握手逻辑）。
// 到达路径点时调用：
//   1) 首次到达：发布 /arm/task（工位号=字段[3]，作业时长=字段[8]，末位为终点标志）
//   2) 中间点：置等待标志并返回 true，当前周期停车等待 /arm/done
//   3) 终点：只发任务帧、不等待回执，返回 false（与原系统一致）
//   4) 该点已发布过任务：返回 false，直接放行
bool TrackedVehicleNavigation::HandleArmTask(std::size_t path_point_index, bool is_final)
{
    if(!_arm_task_enable)
    {
        return false;
    }

    // 安全兜底：处于等待态时不应重复发布任务
    if(arm_task_waiting_)
    {
        cmdVelOutput(0.0, 0.0);
        return true;
    }

    if(static_cast<int>(path_point_index) == arm_handled_path_index_)
    {
        return false;
    }

    arm_handled_path_index_ = static_cast<int>(path_point_index);

    // 仿照原 /PerformTasks：[工位号, 作业时长(秒), 0=中间点/1=终点]
    std_msgs::msg::Float32MultiArray task_msg;
    task_msg.data.push_back(static_cast<float>(_path_point[path_point_index][3]));
    task_msg.data.push_back(static_cast<float>(_path_point[path_point_index][8]));
    task_msg.data.push_back(is_final ? 1.0F : 0.0F);
    pubArmTask->publish(task_msg);

    RCLCPP_WARN(this->get_logger(),
                "【机械臂】发布任务 point_index=%zu 工位号=%.0f 作业时长=%.1f s 终点=%d",
                path_point_index, _path_point[path_point_index][3],
                _path_point[path_point_index][8], is_final ? 1 : 0);

    if(is_final)
    {
        // 与原系统一致：终点任务帧只通知机械臂执行，不等待完成回执。
        return false;
    }

    arm_task_done_ = false;
    arm_task_waiting_ = true;
    arm_wait_start_time_ = this->now();

    cmdVelOutput(0.0, 0.0);
    _vehicle_run_status.data = RunStatus::Paused;
    return true;
}

// 等待机械臂完成（在 vehicle_ctrl_callback 顶部、位姿不更新判断之前调用）。
// 返回 true：仍在等待，当前周期不要继续原导航控制。
// 返回 false：任务已完成（收到 /arm/done 且满足作业时长），放行。
bool TrackedVehicleNavigation::HandleArmWait()
{
    cmdVelOutput(0.0, 0.0);
    _vehicle_run_status.data = RunStatus::Paused;

    double waited = (this->now() - arm_wait_start_time_).seconds();
    double task_duration = 0.0;
    if(arm_handled_path_index_ >= 0)
    {
        task_duration = GetArmTaskDurationByIndex(static_cast<std::size_t>(arm_handled_path_index_));
    }

    // 仿照电气柜逻辑：超时只做周期告警，保持停车，不自动恢复行驶。
    if(waited > _arm_wait_timeout_sec)
    {
        RCLCPP_ERROR_THROTTLE(this->get_logger(), *this->get_clock(), 2000,
                              "【机械臂】等待任务完成超时(%.1f s > %.1f s)，保持停车（point_index=%d）",
                              waited, _arm_wait_timeout_sec, arm_handled_path_index_);
    }

    if(arm_task_done_ && waited >= task_duration)
    {
        RCLCPP_WARN(this->get_logger(),
                    "【机械臂】任务完成：point_index=%d，耗时 %.1f s，恢复原导航控制",
                    arm_handled_path_index_, waited);
        arm_task_waiting_ = false;
        arm_task_done_ = false;
        return false;
    }

    return true;
}

// 读取路径点的机械臂作业时长（第 9 个字段，C++ 下标 [8]，与 ROS 1 原 /PerformTasks 的 data[1] 一致）。
double TrackedVehicleNavigation::GetArmTaskDurationByIndex(std::size_t path_point_index)
{
    if(path_point_index >= MAX_PATH_POINTS)
    {
        return 0.0;
    }
    return _path_point[path_point_index][8];
}

// 机械臂完成回执回调（对应原 /VehicleStartsRun）
void TrackedVehicleNavigation::ArmTaskDoneCallback(const std_msgs::msg::UInt32::SharedPtr paraMsg)
{
    // 只在等待任务时接受回执，避免迟到的历史回执提前放行。
    if(arm_task_waiting_ && paraMsg->data != 0)
    {
        arm_task_done_ = true;
        RCLCPP_WARN(this->get_logger(), "【机械臂】收到完成回执 /arm/done（point_index=%d）",
                    arm_handled_path_index_);
    }
}

// 取消路线 / 新路径下发时清空机械臂任务记忆。
void TrackedVehicleNavigation::ResetArmTaskState()
{
    arm_task_waiting_ = false;
    arm_task_done_ = false;
    arm_handled_path_index_ = -1;
}

// 其他回调函数实现，获取路径信息
void TrackedVehicleNavigation::PathPointCallback(const std_msgs::msg::Float64MultiArray::SharedPtr paraMsg) {
    _rev_path_point.data = paraMsg->data;
    _path_point_number  = paraMsg->data.size() / 9;
    _Received_path   = true;
    _path_point_count  = 0;

    // 新增：新路径下发时清空机械臂任务记忆，避免沿用上一轮的点位状态
    ResetArmTaskState();
    
    //路径点一维数组转化为二维数组
    for(size_t i = 0; i < _path_point_number; i++) 
    {
       for(size_t j = 0; j < 9; j++) 
       {
          _path_point[i][j] = _rev_path_point.data[i * 9 + j + 1];
          printf(" %f ", _path_point[i][j]);
       }
       printf("  \n"); 
    }
    
    //发布定位模式
    _location_mode.data = _path_point[0][6];
    pubLocationMode->publish(_location_mode);
    
    std_msgs::msg::UInt8 _path_received;
    _path_received.data = obstacle_avoidance_mode::default_path;
    pubPathReceivedFinish->publish(_path_received);
    
    _rev_path_point.data.clear();
    RCLCPP_INFO(this->get_logger(), "_Received_path: %d", _Received_path);
    RCLCPP_INFO(this->get_logger(), "_path_point_number: %zu", _path_point_number);
}

//路径点左
void TrackedVehicleNavigation::PathPointLeftCallback(const std_msgs::msg::Float64MultiArray::SharedPtr paraMsg) {
    _rev_path_point_left.data = paraMsg->data;
    _path_point_number_left  = paraMsg->data.size() / 9;
    _Received_path_left   = true;
    //_path_point_count_left  = 0;
    
    //路径点一维数组转化为二维数组
    for(size_t i = 0; i < _path_point_number_left; i++) 
    {
       for(size_t j = 0; j < 9; j++) 
       {
          _path_point_left[i][j] = _rev_path_point_left.data[i * 9 + j + 1];
          printf(" %f ", _path_point_left[i][j]);
       }
       printf("  \n"); 
    }
    
    std_msgs::msg::UInt8 _path_received;
    _path_received.data = obstacle_avoidance_mode::left_path;
    pubPathReceivedFinish->publish(_path_received);
    
    _rev_path_point_left.data.clear();
    RCLCPP_INFO(this->get_logger(), "_Received_path_left: %d", _Received_path_left);
    RCLCPP_INFO(this->get_logger(), "_path_point_number_left: %zu", _path_point_number_left);
}

//路径点右
void TrackedVehicleNavigation::PathPointRightCallback(const std_msgs::msg::Float64MultiArray::SharedPtr paraMsg) {
    _rev_path_point_right.data = paraMsg->data;
    _path_point_number_right  = paraMsg->data.size() / 9;
    _Received_path_right   = true;
    //_path_point_count_right  = 0;
    
    //路径点一维数组转化为二维数组
    for(size_t i = 0; i < _path_point_number_right; i++) 
    {
       for(size_t j = 0; j < 9; j++) 
       {
          _path_point_right[i][j] = _rev_path_point_right.data[i * 9 + j + 1];
          printf(" %f ", _path_point_right[i][j]);
       }
       printf("  \n"); 
    }
    
    std_msgs::msg::UInt8 _path_received;
    _path_received.data = obstacle_avoidance_mode::right_path;
    pubPathReceivedFinish->publish(_path_received);
    
    _rev_path_point_right.data.clear();
    RCLCPP_INFO(this->get_logger(), "_Received_path_right: %d", _Received_path_right);
    RCLCPP_INFO(this->get_logger(), "_path_point_number_right: %zu", _path_point_number_right);
}

//车辆启动，暂停
void TrackedVehicleNavigation::VchicleRunStarCallback(const std_msgs::msg::UInt8::SharedPtr paraMsg) {
    _run_vehicle = paraMsg->data;
    RCLCPP_INFO(this->get_logger(), "_run_vehicle: %d", _run_vehicle);
}

//路径模式切换
void TrackedVehicleNavigation::ObstacleAvoidanceCallback(const std_msgs::msg::UInt8::SharedPtr paraMsg) {
    _obstacle_avoidance = paraMsg->data;
    RCLCPP_INFO(this->get_logger(), "_obstacle_avoidance: %d", _obstacle_avoidance);
}

//取消路线
void TrackedVehicleNavigation::CloseRouteCallback(const std_msgs::msg::UInt8::SharedPtr paraMsg) {
    _close_route = paraMsg->data;
    _path_point_count = 0;
    _run_mode_switch = false;
    _Received_path = false;
    _Received_path_right = false;
    _Received_path_left = false;
    _run_vehicle = 0;
    _obstacle_avoidance = 0;
    _spin_vehicle_flag = false;

    // 新增：取消路线时清空电气柜等待状态和已处理路径点编号
    // ResetElectricBinState();

    // 新增：取消路线时同时清空机械臂任务状态
    ResetArmTaskState();

    for(size_t i = 0; i < _path_point_number; i++) 
    {
       for(size_t j = 0; j < 9; j++) 
       {
          _path_point[i][j] = 0;
          _path_point_left[i][j] = 0;
          _path_point_right[i][j] = 0;
          printf(" %f ", _path_point[i][j]);
       }
       printf("  \n"); 
    }
    
    std_msgs::msg::UInt8 _close_route_finish;
    _close_route_finish.data = RunStatus::Canceled;
    pubCloseRouteFinish->publish(_close_route_finish);
    
    _vehicle_run_status.data = RunStatus::Ready;
    
    RCLCPP_INFO(this->get_logger(), "_close_route: %d", _close_route);
}

//或者车辆自转的角度
void TrackedVehicleNavigation::VchicleSpinCallback(const std_msgs::msg::Float32::SharedPtr paraMsg) {
    _goal_spin_yaw = paraMsg->data;
    _spin_vehicle_flag = true;
    RCLCPP_INFO(this->get_logger(), "_spin_vehicle: %f", _goal_spin_yaw);
}


bool TrackedVehicleNavigation::ObstacleAvoidancePathSelect() {
    //获取路径
    obstacle_avoidance_mode mode_value = static_cast<obstacle_avoidance_mode>(_obstacle_avoidance);
    if(mode_value == default_path)
    {
        if(_Received_path == false)
        {
            //cmdVelOutput(0.0, 0.0);
            RCLCPP_INFO(this->get_logger(), "[car _Received_path: %d] ",_Received_path);
            return false;
        }
        _starA.x = _path_point[_path_point_count][0];
        _starA.y = _path_point[_path_point_count][1];
        _endB.x = _path_point[_path_point_count + 1][0];
        _endB.y = _path_point[_path_point_count + 1][1];  
        //获取点属性-速度、模式、
        _init_point_speed = _path_point[_path_point_count][4];//点速度,终点目标
        _run_mode = _path_point[_path_point_count][5]; //起点,转弯模式0，自转模式1
    }
    else if(mode_value == left_path)
    {   
        if(_Received_path_left == false)
        {
            //cmdVelOutput(0.0, 0.0);
            RCLCPP_INFO(this->get_logger(), "[car _Received_path_left: %d] ",_Received_path_left);
            return false;
        }
        _starA.x = _path_point_left[_path_point_count][0];    
        _starA.y = _path_point_left[_path_point_count][1];
        _endB.x = _path_point_left[_path_point_count + 1][0];
        _endB.y = _path_point_left[_path_point_count + 1][1];  
        _init_point_speed = _path_point_left[_path_point_count][4];//点速度,终点目标
        _run_mode = _path_point_left[_path_point_count][5]; //起点,转弯模式0，自转模式1
    }
    else if(mode_value == right_path)
    {   
        if(_Received_path_right == false)
        {
            //cmdVelOutput(0.0, 0.0);
            RCLCPP_INFO(this->get_logger(), "[car _Received_path_right: %d] ",_Received_path_right);
            return false; 
        }
        _starA.x = _path_point_right[_path_point_count][0];  
        _starA.y = _path_point_right[_path_point_count][1];
        _endB.x = _path_point_right[_path_point_count + 1][0];
        _endB.y = _path_point_right[_path_point_count + 1][1];  
         //获取点属性-速度、模式、
        _init_point_speed = _path_point_right[_path_point_count][4];//点速度,终点目标
        _run_mode = _path_point_right[_path_point_count][5]; //起点,转弯模式0，自转模式1
    }
    else if(mode_value == parking)
    {
        //cmdVelOutput(0.0, 0.0);
        return false;
    }     

    return true;      
}
