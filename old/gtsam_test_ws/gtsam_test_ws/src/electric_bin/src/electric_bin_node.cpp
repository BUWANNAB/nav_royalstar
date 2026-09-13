// 所有必需的头文件（一次性包含，无需额外添加）
#include "rclcpp/rclcpp.hpp"
#include "std_msgs/msg/bool.hpp"
#include "std_msgs/msg/string.hpp"
#include <modbus/modbus-tcp.h>
#include <chrono>
#include <thread>
#include <string>
#include <errno.h>
#include "std_msgs/msg/u_int8.hpp"
#include "std_msgs/msg/u_int8_multi_array.hpp"
using namespace std::chrono_literals;
using std::placeholders::_1;

// 中盛WIFI IO模块硬件配置（严格匹配手册默认参数，修正端口错误）
//#define MODBUS_IP        "192.168.43.1"  // 手册2.3节默认IP
//#define MODBUS_PORT      1883             // 修正：Modbus TCP默认端口502（1883是MQTT）
//#define MODBUS_SLAVE_ID  1               // 手册3.6节默认站号
//#define DELAY_TIME       500ms           // 硬件动作延时（防误触发）

// Modbus IO地址映射（严格匹配手册3.2/3.3节，与Y1/Y2/X1/X2/X3绑定）
//#define COIL_Y1_OPEN     0x0000  // 手册3.2节：0000H=通道1输出（Y1）
//#define COIL_Y2_CLOSE    0x0001  // 手册3.2节：0001H=通道2输出（Y2）
//#define DISC_X1_SW1      0x0000  // 手册3.3节：0000H=通道1输入（X1）-舱门全开
//#define DISC_X2_SW2      0x0001  // 手册3.3节：0001H=通道2输入（X2）-小车入仓
//#define DISC_X3_SW3      0x0002  // 手册3.3节：0002H=通道3输入（X3）-舱门全关
//#define MAX_OPERATE_TIME 10000ms //最大持续时间 10s
// 电气仓状态枚举（简化逻辑判断）
enum class BinState {
    DOOR_CLOSED,   // 舱门全关（初始状态）
    DOOR_OPENING,  // 舱门打开中
    DOOR_CLOSING,  // 舱门关闭中
    DOOR_OPENED,   // 舱门全开
    CAR_INSIDE     // 小车已入仓（X2触发）
   
};

// IO寄存器结构，用于一次性存储所有端口状态
struct IORegister {
    int x1_state; // X1-舱门全开
    int x2_state; // X2-小车入仓
    int x3_state; // X3-舱门全关
    int y1_state;
    int y2_state;
};

// 核心ROS2节点类
class ElectricBinNode : public rclcpp::Node
{
   public:
    // 节点构造函数（初始化所有组件）
    ElectricBinNode() : Node("electric_bin_node"), bin_state_(BinState::DOOR_CLOSED), allow_published_(false)
    {
        RCLCPP_INFO(this->get_logger(), "开始初始化电气仓节点...");
 	// 1. 声明和获取所有参数
	this->declare_parameter("MODBUS_IP", "192.168.43.1");
	this->declare_parameter("MODBUS_PORT", 1883);
	this->declare_parameter("MODBUS_SLAVE_ID", 1);
	//this->declare_parameter("COIL_Y1_OPEN", 0);
	//this->declare_parameter("COIL_Y2_CLOSE", 1);
	//this->declare_parameter("DISC_X1_SW1", 0);
	//this->declare_parameter("DISC_X2_SW2", 1);
	//this->declare_parameter("DISC_X3_SW3", 2);
	//this->declare_parameter("DELAY_TIME", 500);
	//this->declare_parameter("MAX_OPERATE_TIME", 10000);
	//this->declare_parameter("SAFE_DISTANCE_TOPIC", "/car_to_bin/safe_distance");
	//this->declare_parameter("LEAVE_CMD_TOPIC", "/car_to_bin/leave_cmd");
	//this->declare_parameter("ALLOW_TOPIC", "/bin_to_car/allow");
	//this->declare_parameter("STATE_TOPIC", "/bin_to_car/state");
	//this->declare_parameter("MODBUS_TIMEOUT_SEC", 5);
	this->declare_parameter("IO_READ_INTERVAL_MS", 100);
	this->declare_parameter("ACTION_TIMEOUT", 10);

	this->get_parameter("MODBUS_IP", MODBUS_IP);
	this->get_parameter("MODBUS_PORT", MODBUS_PORT);
	this->get_parameter("MODBUS_SLAVE_ID", MODBUS_SLAVE_ID);
	//this->get_parameter("COIL_Y1_OPEN", COIL_Y1_OPEN);
	//this->get_parameter("COIL_Y2_CLOSE", COIL_Y2_CLOSE);
	//this->get_parameter("DISC_X1_SW1", DISC_X1_SW1);
	//this->get_parameter("DISC_X2_SW2", DISC_X2_SW2);
	//this->get_parameter("DISC_X3_SW3", DISC_X3_SW3);
	//this->get_parameter("DELAY_TIME", DELAY_TIME);
	//this->get_parameter("MAX_OPERATE_TIME", MAX_OPERATE_TIME);
	//this->get_parameter("SAFE_DISTANCE_TOPIC", SAFE_DISTANCE_TOPIC);
	//this->get_parameter("LEAVE_CMD_TOPIC", LEAVE_CMD_TOPIC);
	//this->get_parameter("ALLOW_TOPIC", ALLOW_TOPIC);
	//this->get_parameter("STATE_TOPIC", STATE_TOPIC);
	//this->get_parameter("MODBUS_TIMEOUT_SEC", MODBUS_TIMEOUT_SEC);
	this->get_parameter("IO_READ_INTERVAL_MS", IO_READ_INTERVAL_MS);
	this->get_parameter("ACTION_TIMEOUT", ACTION_TIMEOUT);
            
    // 修改话题定义：
    // 1. 将所有状态信息集成到一个话题
    INTEGRATED_STATE_TOPIC = "/bin_to_car/integrated_state";
    
    // 2. 合并开门和关门指令为一个话题
    DOOR_CMD_TOPIC = "/car_to_bin/door_cmd";
     
    RCLCPP_INFO(this->get_logger(), "参数加载完成: IP=%s, Port=%d", 
                MODBUS_IP.c_str(), MODBUS_PORT);
    
   
        // 2. 初始化Modbus TCP客户端（硬件通讯核心，严格匹配手册）
        modbus_ctx_ = modbus_new_tcp(MODBUS_IP.c_str(), MODBUS_PORT);
        if (modbus_ctx_ == nullptr) {
            RCLCPP_FATAL(this->get_logger(), "Modbus TCP客户端初始化失败！请检查libmodbus是否安装（sudo apt install libmodbus-dev）");
            rclcpp::shutdown();
            return;
        }
        // 设置Modbus从站号（手册默认1）
        modbus_set_slave(modbus_ctx_, MODBUS_SLAVE_ID);
        // 设置超时3秒（防止通讯卡顿，不关闭端口）
        modbus_set_response_timeout(modbus_ctx_, MODBUS_TIMEOUT_SEC, 0);
        // 连接中盛IO模块
        if (modbus_connect(modbus_ctx_) == -1) {
            RCLCPP_ERROR(this->get_logger(), "连接中盛IO模块失败: %s | 检查：模块是否上电/电脑是否连ZSKJ_XXX WIFI/接线是否正确", modbus_strerror(errno));
            modbus_free(modbus_ctx_);
            modbus_ctx_ = nullptr;
        } else {
          RCLCPP_INFO(this->get_logger(), "✅ 成功连接中盛IO模块: %s:%d (站号%d)", 
           MODBUS_IP.c_str(), MODBUS_PORT, MODBUS_SLAVE_ID);  // ✅ 正确
        }
    
         // 2. 创建参数更新话题订阅
        sub_param_update_ = this->create_subscription<std_msgs::msg::UInt8>(
        "/param_update", 10, std::bind(&ElectricBinNode::param_update_callback, this, _1));
        // 修改：合并开门和关门指令为一个话题
        sub_door_cmd_ = this->create_subscription<std_msgs::msg::String>(
            "/car_to_bin/door_cmd", 10, std::bind(&ElectricBinNode::door_cmd_callback, this, _1));
        // 修改：创建一个集成所有状态的发布者
        pub_integrated_state_ = this->create_publisher<std_msgs::msg::UInt8MultiArray>("/bin_to_car/integrated_state", 10);
        
        // ROS2话题发布（向小车发送状态/允许指令）
        //pub_allow_ = this->create_publisher<std_msgs::msg::Bool>("/bin_to_car/allow", 10);
       // pub_state_ = this->create_publisher<std_msgs::msg::String>("/bin_to_car/state", 10);
         // 创建电平状态发布器
       // pub_x_y_state_ = this->create_publisher<std_msgs::msg::String>("/bin_to_car/x_y_state", 10);
       
      

        // 5. 定时检测IO状态（100ms一次，实时获取微动开关/舱门状态）
        timer_io_detect_ = this->create_wall_timer(
             std::chrono::milliseconds(IO_READ_INTERVAL_MS),   
             std::bind(&ElectricBinNode::io_detect_callback, this));

        // 6. 初始化硬件输出口（关闭Y1/Y2，防止上电误动作，匹配手册0=关闭）
        write_coil(COIL_Y1_OPEN, false);
        write_coil(COIL_Y2_CLOSE, false);

        RCLCPP_INFO(this->get_logger(), "✅ 电气仓节点初始化完成！初始状态：舱门全关");
    }

    // 节点析构函数（销毁时复位硬件/断开通讯）
    ~ElectricBinNode()
    {
        if (modbus_ctx_) {
            // 复位所有输出口（手册0=关闭）
            write_coil(COIL_Y1_OPEN, false);
            write_coil(COIL_Y2_CLOSE, false);
            // 断开Modbus连接并释放资源
            modbus_close(modbus_ctx_);
            modbus_free(modbus_ctx_);
        }
        RCLCPP_INFO(this->get_logger(), "✅ 电气仓节点销毁，硬件IO已复位");
    }
    
  

	private:
	// 从YAML加载的参数
	    std::string MODBUS_IP;
	    int MODBUS_PORT;
	    int MODBUS_SLAVE_ID;
	    int ACTION_TIMEOUT;
	    int IO_READ_INTERVAL_MS;



    
    const int COIL_Y1_OPEN = 0;
    const int COIL_Y2_CLOSE = 1;
    const int DISC_X1_SW1 = 0;
    const int DISC_X2_SW2 = 1;
    const int DISC_X3_SW3 = 2;
    const int DELAY_TIME = 500;           // 单位：毫秒
    const int MODBUS_TIMEOUT_SEC = 5;    // 单位：秒
   
    
    std::string INTEGRATED_STATE_TOPIC;
    std::string DOOR_CMD_TOPIC;
    // Modbus硬件通讯相关
    modbus_t *modbus_ctx_ = nullptr;  // 初始化空指针，避免野指针
    // ROS2组件相关
      // 添加以下声明：
    rclcpp::Publisher<std_msgs::msg::UInt8MultiArray>::SharedPtr pub_integrated_state_;
    rclcpp::Subscription<std_msgs::msg::UInt8>::SharedPtr sub_param_update_;
    rclcpp::Subscription<std_msgs::msg::String>::SharedPtr sub_door_cmd_;  // ✅ 添加这行
    rclcpp::TimerBase::SharedPtr timer_io_detect_;
    
    // 函数声明
    //void param_update_callback(const std_msgs::msg::UInt8::SharedPtr msg);
    //void recreate_topics_if_needed();
    //void update_timer_if_needed();
    //void io_detect_callback();
    //void publish_integrated_state();
    //void update_bin_state_from_io();
    //void handle_state_logic();
    //void door_cmd_callback(const std_msgs::msg::String::SharedPtr msg);
    // ... 其他函数声明
    
    // 变量声明
    BinState bin_state_;
    IORegister io_register_{1, 1, 1, 0, 0};
    bool allow_published_;
 
    std::chrono::steady_clock::time_point action_start_time_;
    bool is_action_timeout_ = false;
    uint8_t y1_state_ = 0;
    uint8_t y2_state_ = 0;
  
 

    // 【Modbus工具函数】重连模块（通讯中断时自动重试，不重复创建上下文）
    bool modbus_reconnect()
    {
        // 如果已有上下文，先释放
        if (modbus_ctx_) {
            modbus_close(modbus_ctx_);
            modbus_free(modbus_ctx_);
            modbus_ctx_ = nullptr;
        }
        // 重建上下文
        modbus_ctx_ = modbus_new_tcp(MODBUS_IP.c_str(), MODBUS_PORT);
        if (modbus_ctx_ == nullptr) {
            RCLCPP_ERROR(this->get_logger(), "Modbus上下文重建失败！");
            return false;
        }
        modbus_set_slave(modbus_ctx_, MODBUS_SLAVE_ID);
        modbus_set_response_timeout(modbus_ctx_, MODBUS_TIMEOUT_SEC, 0);
        // 连接模块
        if (modbus_connect(modbus_ctx_) == -1) {
            RCLCPP_WARN(this->get_logger(), "Modbus重连失败: %s", modbus_strerror(errno));
            modbus_free(modbus_ctx_);
            modbus_ctx_ = nullptr;
            return false;
        }
        RCLCPP_INFO(this->get_logger(), "✅ Modbus模块重连成功！");
        return true;
    }

    // 【Modbus工具函数】写线圈寄存器（控制Y1/Y2，匹配手册3.2节：0=关闭/1=开启）
    bool write_coil(int addr, bool value)
    {
        if (modbus_ctx_ == nullptr && !modbus_reconnect()) {
            return false;
        }
        // modbus_write_bit：05H功能码，严格匹配手册3.6节写单个线圈指令
        int ret = modbus_write_bit(modbus_ctx_, addr, value ? 1 : 0);
        if (ret == -1) {
            RCLCPP_WARN(this->get_logger(), "写输出口Y%d失败: %s（地址0x%04X）", (addr==0?1:2), modbus_strerror(errno), addr);
            return false; // 仅报错，不关闭端口（核心修正：防止后续通讯中断）
        }
          // 更新内部状态
        if (addr == COIL_Y1_OPEN) {
            io_register_.y1_state = value ? 1 : 0;
        } else if (addr == COIL_Y2_CLOSE) {
            io_register_.y2_state = value ? 1 : 0;
        }
        return true;
    }

    // 【Modbus工具函数】一次性读取所有IO状态，存储到寄存器中
    bool read_all_io_states()
    {
        if (modbus_ctx_ == nullptr && !modbus_reconnect()) {
            // 连接失败时保持上次IO状态
            RCLCPP_WARN(this->get_logger(), "Modbus连接失败，保持上次IO状态");
            return false;
        }

        uint8_t dest[3] = {1, 1, 1}; // 初始化为1（未触发，高电平）
        int ret = modbus_read_input_bits(modbus_ctx_, DISC_X1_SW1, 3, dest);
        if (ret == -1) {
            // 读取失败时保持上次IO状态
            RCLCPP_WARN(this->get_logger(), "批量读取IO状态失败: %s，保持上次IO状态", modbus_strerror(errno));
            return false;
        }

        // NPN低电平触发：0表示触发，1表示未触发
        io_register_.x1_state = (dest[0] == 0) ? 1 : 0;
        io_register_.x2_state = (dest[1] == 0) ? 1 : 0;
        io_register_.x3_state = (dest[2] == 0) ? 1 : 0;

         // 打印IO状态（调试用）
        RCLCPP_DEBUG(this->get_logger(), "IO状态: X1=%d, X2=%d, X3=%d, Y1=%d, Y2=%d", 
                    io_register_.x1_state, io_register_.x2_state, io_register_.x3_state, 
                    io_register_.y1_state, io_register_.y2_state);

        return true;
    }
    
    // 【超时检测】检查舱门动作是否超时（防止硬件故障导致卡死）
     bool check_action_timeout()
   {
    // 只有在动作中（开门/关门）才检测超时
    if (bin_state_ != BinState::DOOR_OPENING && bin_state_ != BinState::DOOR_CLOSING) {
        
        return false;
    }

    // 计算当前动作已持续时间
    auto now = std::chrono::steady_clock::now();
    auto duration = std::chrono::duration_cast<std::chrono::seconds>(now - action_start_time_);

    // 超过阈值则判定超时
    if (duration >= std::chrono::seconds(ACTION_TIMEOUT)) {
        is_action_timeout_ = true;
        RCLCPP_ERROR(this->get_logger(), "⚠️ 舱门动作超时！%s超过%d秒未触发到位开关（X%d），强制复位硬件",
                    (bin_state_ == BinState::DOOR_OPENING ? "开门" : "关门"),
                    ACTION_TIMEOUT,
                    (bin_state_ == BinState::DOOR_OPENING ? 1 : 3));
        // 强制复位输出口
        write_coil(COIL_Y1_OPEN, false);
        write_coil(COIL_Y2_CLOSE, false);
        return true;
    }
         return false;
   }


    // 使用新参数重新连接Modbus
    bool modbus_reconnect_with_new_params()
    {
        // 如果已有上下文，先释放
        if (modbus_ctx_) {
            modbus_close(modbus_ctx_);
            modbus_free(modbus_ctx_);
            modbus_ctx_ = nullptr;
        }
        
        // 使用新参数重建上下文
        modbus_ctx_ = modbus_new_tcp(MODBUS_IP.c_str(), MODBUS_PORT);
        if (modbus_ctx_ == nullptr) {
            RCLCPP_ERROR(this->get_logger(), "Modbus上下文重建失败！");
            return false;
        }
        
        modbus_set_slave(modbus_ctx_, MODBUS_SLAVE_ID);
        modbus_set_response_timeout(modbus_ctx_, MODBUS_TIMEOUT_SEC, 0);
        
        // 使用新IP和端口连接
        if (modbus_connect(modbus_ctx_) == -1) {
            RCLCPP_WARN(this->get_logger(), "Modbus重连失败: %s", modbus_strerror(errno));
            modbus_free(modbus_ctx_);
            modbus_ctx_ = nullptr;
            return false;
        }
        
        RCLCPP_INFO(this->get_logger(), "✅ Modbus模块使用新参数重连成功！");
        RCLCPP_INFO(this->get_logger(), "  新地址: %s:%d, 从站ID: %d", 
                   MODBUS_IP.c_str(), MODBUS_PORT, MODBUS_SLAVE_ID);
        return true;
    }
    
    // 修改：合并的指令回调函数
    void door_cmd_callback(const std_msgs::msg::String::SharedPtr msg)
    {
        if (msg->data == "open_door") {
            RCLCPP_INFO(this->get_logger(), "📤 收到开门指令");
             is_action_timeout_ = false;
            write_coil(COIL_Y2_CLOSE, false);
            std::this_thread::sleep_for(std::chrono::milliseconds(DELAY_TIME));
            write_coil(COIL_Y1_OPEN, true);
            
            action_start_time_ = std::chrono::steady_clock::now();
            bin_state_ = BinState::DOOR_OPENING;
            RCLCPP_INFO(this->get_logger(), "🔓 开始打开舱门...");
            
        } else if (msg->data == "close_door") {
            RCLCPP_INFO(this->get_logger(), "🛑 收到关门指令");
             is_action_timeout_ = false;
            write_coil(COIL_Y1_OPEN, false);
            std::this_thread::sleep_for(std::chrono::milliseconds(DELAY_TIME));
            write_coil(COIL_Y2_CLOSE, true);
            
            action_start_time_ = std::chrono::steady_clock::now();
            bin_state_ = BinState::DOOR_CLOSING;
            RCLCPP_INFO(this->get_logger(), "🔒 开始关闭舱门...");
        } else {
            RCLCPP_WARN(this->get_logger(), "未知指令: %s", msg->data.c_str());
        }
    }
     // 修改：发布集成状态
    void publish_integrated_state()
    {
        auto msg = std_msgs::msg::UInt8MultiArray();
        
        // 数组顺序: x1, x2, x3, Y1, Y2, door_closed, door_closing, door_opened, door_opening
        // 每个值都是0或1
        msg.data.resize(6);
        
        // X状态
        msg.data[0] = static_cast<uint8_t>(io_register_.x1_state);
        msg.data[1] = static_cast<uint8_t>(io_register_.x2_state);
        msg.data[2] = static_cast<uint8_t>(io_register_.x3_state);
        
        // Y状态
        msg.data[3] = static_cast<uint8_t>(io_register_.y1_state);
        msg.data[4] = static_cast<uint8_t>(io_register_.y2_state);
        
        // 舱门状态（互斥，同一时间只有一个为1）
       // msg.data[5] = (bin_state_ == BinState::DOOR_CLOSED) ? 1 : 0;   // door_closed
       // msg.data[6] = (bin_state_ == BinState::DOOR_CLOSING) ? 1 : 0;  // door_closing
       // msg.data[7] = (bin_state_ == BinState::DOOR_OPENED) ? 1 : 0;   // door_opened
       // msg.data[8] = (bin_state_ == BinState::DOOR_OPENING) ? 1 : 0;  // door_opening
        msg.data[5] = is_action_timeout_ ? 1 : 0;

        pub_integrated_state_->publish(msg);
    }
    
  
    // 参数重加载回调函数
    void param_update_callback(const std_msgs::msg::UInt8::SharedPtr msg)
    {
        RCLCPP_INFO(this->get_logger(), "收到参数更新消息: data=%u", msg->data);
        
        // 只有当 data = 1 时才执行参数重加载
        if (msg->data == 1) {
            RCLCPP_INFO(this->get_logger(), "开始执行参数重加载...");
		
            this->get_parameter("MODBUS_IP", MODBUS_IP);
            this->get_parameter("MODBUS_PORT", MODBUS_PORT);
            this->get_parameter("MODBUS_SLAVE_ID", MODBUS_SLAVE_ID);
            //this->get_parameter("COIL_Y1_OPEN", COIL_Y1_OPEN);
            //this->get_parameter("COIL_Y2_CLOSE", COIL_Y2_CLOSE);
            //this->get_parameter("DISC_X1_SW1", DISC_X1_SW1);
            //this->get_parameter("DISC_X2_SW2", DISC_X2_SW2);
            //this->get_parameter("DISC_X3_SW3", DISC_X3_SW3);
            //this->get_parameter("DELAY_TIME", DELAY_TIME);
            //this->get_parameter("MAX_OPERATE_TIME", MAX_OPERATE_TIME);
            //this->get_parameter("SAFE_DISTANCE_TOPIC", SAFE_DISTANCE_TOPIC);
            //this->get_parameter("LEAVE_CMD_TOPIC", LEAVE_CMD_TOPIC);
            //this->get_parameter("ALLOW_TOPIC", ALLOW_TOPIC);
            //this->get_parameter("STATE_TOPIC", STATE_TOPIC);
            //this->get_parameter("MODBUS_TIMEOUT_SEC", MODBUS_TIMEOUT_SEC);
            this->get_parameter("IO_READ_INTERVAL_MS", IO_READ_INTERVAL_MS);
            this->get_parameter("ACTION_TIMEOUT", ACTION_TIMEOUT);
            
            RCLCPP_INFO(this->get_logger(), "✅ 参数重加载完成");
           // RCLCPP_INFO(this->get_logger(), "  新参数: IP=%s, Port=%d, Delay=%dms", 
                      // MODBUS_IP.c_str(), MODBUS_PORT, DELAY_TIME);
            
            // 2. 如果Modbus连接已建立，使用新参数重新连接
            /*if (modbus_ctx_ != nullptr) {
                RCLCPP_INFO(this->get_logger(), "使用新参数重新连接Modbus...");
                modbus_reconnect_with_new_params();
            }*/
            
            // 3. 重新创建话题（如果话题名称变更）
            //recreate_topics_if_needed();
            
            // 4. 更新定时器（如果间隔变更）
            //update_timer_if_needed();
        }
    }
	void recreate_topics_if_needed()
	{
	    RCLCPP_INFO(this->get_logger(), "检查话题是否需要重新创建...");

	    // 修改：合并开门和关门指令为一个话题
	    sub_door_cmd_ = this->create_subscription<std_msgs::msg::String>(
		"/car_to_bin/door_cmd", 10, std::bind(&ElectricBinNode::door_cmd_callback, this, _1));

	    // 参数更新话题订阅（用于重新加载参数）
	    sub_param_update_ = this->create_subscription<std_msgs::msg::UInt8>(
		"/param_update", 10, std::bind(&ElectricBinNode::param_update_callback, this, _1));

	    // 修改：集成所有状态到一个发布话题
	    pub_integrated_state_ = this->create_publisher<std_msgs::msg::UInt8MultiArray>(
		"/bin_to_car/integrated_state", 10);

	    RCLCPP_INFO(this->get_logger(), "✅ 话题已使用新名称重新创建");
	}
    // 更新定时器（如果时间间隔变更）
    void update_timer_if_needed()
    {
        // 取消现有定时器
        if (timer_io_detect_) {
            timer_io_detect_->cancel();
        }
        
        // 使用新时间间隔重新创建定时器
        timer_io_detect_ = this->create_wall_timer(
            std::chrono::milliseconds(IO_READ_INTERVAL_MS), 
            std::bind(&ElectricBinNode::io_detect_callback, this));
        
        RCLCPP_INFO(this->get_logger(), "✅ IO检测定时器已更新: %dms", IO_READ_INTERVAL_MS);
    }
    // 【定时回调】100ms检测一次IO状态，更新微动开关/舱门状态
    void io_detect_callback()
    {
        bool read_success = read_all_io_states();
        
        // 只有在IO状态读取成功时才处理状态逻辑
        if (read_success) {
      
        handle_state_logic();  // 处理状态逻辑
        if (!check_action_timeout()) {
                // 超时检查通过，处理正常状态逻辑
                handle_state_logic();
            }
         
        } else {
            RCLCPP_WARN(this->get_logger(), "IO状态读取失败，跳过状态逻辑处理");
        }

          // 修改：发布集成状态
        publish_integrated_state();
       
    }

	   void handle_state_logic()
	{
	    // 首先根据IO状态更新BinState
	    update_bin_state_from_io();
	    bool timeout = check_action_timeout();
	    if (timeout) {
		// 超时后强制恢复为初始状态（舱门关闭）
		bin_state_ = BinState::DOOR_CLOSED;
		allow_published_ = false;
		return; // 超时后跳过后续逻辑
	    }

	    // 根据更新后的BinState执行相应的动作
	    switch (bin_state_) {
		case BinState::DOOR_OPENING:
		    // 舱门打开中，检测X1触发
		    if (io_register_.x1_state == 0) {
		        // X1触发，说明门已完全打开，关断Y1
		        write_coil(COIL_Y1_OPEN, false);
		        bin_state_ = BinState::DOOR_OPENED;
		        allow_published_ = true;
		        RCLCPP_INFO(this->get_logger(), "✅ 舱门已完全打开（X1触发），关断Y1");
		    }
		    break;
		
		case BinState::DOOR_CLOSING:
		    // 舱门关闭中，检测X3触发
		    if (io_register_.x3_state == 0) {
		        // X3触发，说明门已完全关闭，关断Y2
		        write_coil(COIL_Y2_CLOSE, false);
		        bin_state_ = BinState::DOOR_CLOSED;
		        allow_published_ = false;
		        RCLCPP_INFO(this->get_logger(), "✅ 舱门已完全关闭（X3触发），关断Y2"); 
		    }
		    break;

		// 其他状态逻辑保持不变
		case BinState::DOOR_CLOSED:
		case BinState::DOOR_OPENED:
		case BinState::CAR_INSIDE:
		default:
		    break;
	    }
	}

	  void update_bin_state_from_io()  
	{
	    // 状态优先级：舱门动作中 > X1/X3 > X2 > 默认
	    if (bin_state_ == BinState::DOOR_OPENING || bin_state_ == BinState::DOOR_CLOSING) {
		// 舱门正在动作，保持当前状态
		return;
	    }
	    
	    // 根据IO状态更新BinState
	    if (io_register_.x3_state == 0) {
		// X3触发（舱门全关）
		bin_state_ = BinState::DOOR_CLOSED;
		allow_published_ = false;
	    } else if (io_register_.x1_state == 0) {
		// X1触发（舱门全开）
		bin_state_ = BinState::DOOR_OPENED;
		allow_published_ = true;
	    } else if (io_register_.x2_state == 0) {
		// X2触发（小车入仓）
		bin_state_ = BinState::CAR_INSIDE;
		allow_published_ = false;
	    }
	}
};

 

    
  



// 主函数（ROS2节点入口）
int main(int argc, char * argv[])
{
    // 初始化ROS2系统
    rclcpp::init(argc, argv);
    // 创建电气仓节点实例
    auto node = std::make_shared<ElectricBinNode>();
    // 运行节点（阻塞式，直到节点关闭）
    rclcpp::spin(node);
    // 关闭ROS2系统
    rclcpp::shutdown();
    return 0;
}
