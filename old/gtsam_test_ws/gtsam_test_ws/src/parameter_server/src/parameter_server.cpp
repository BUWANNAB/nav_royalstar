#include <rclcpp/rclcpp.hpp>
#include <rcl_interfaces/msg/set_parameters_result.hpp>
#include <rcl_interfaces/msg/parameter_value.hpp>
#include <std_srvs/srv/trigger.hpp>
#include <yaml-cpp/yaml.h>
#include "std_msgs/msg/u_int8.hpp"

#include <mysql_driver.h>
#include <mysql_connection.h>
#include <cppconn/statement.h>
#include <cppconn/resultset.h>
#include <cppconn/exception.h>

#include <functional>
#include <map>
#include <vector>
#include <string>
#include <any>
#include <fstream> 

using sql::Driver;
using sql::Connection;
using sql::Statement;
using sql::ResultSet;
using sql::SQLException;

// 参数结构体定义
struct ParameterSet {
    // GNSS
    double ref_latitude = 34.9717758;
    double ref_longitude = 118.4472284;
    double ref_height = 63.9963;
    double gnss_offset_x = 0.38;
    double gnss_offset_y = -0.45;

    // Lidar
    std::string map_path = "/home/lyjqr/gtsam_test_ws/GlobalMap.pcd";
    double voxel_leaf_size = 0.5;
    double scan_max_range = 30.0;
    double scan_min_range = 1.0;

    // TF
    std::string target_frame = "map";
    std::string source_frame = "fusion_link";
    double publish_rate = 50.0;

    // Nav
    double lookahead_dist = 1.0;
    double max_velocity = 1.0;
    double min_velocity = 1.0;
    double xy_goal_tolerance = 0.05;
    double xy_middle_tolerance = 0.2;
    double yaw_goal_tolerance = 5.0;
    double wheel_base = 1.0;
    double angular_velocity_limit = 0.25;
    double proportion = 1.0;
    std::string authorized_hardware_id = ".da28aa1f977f245db8d2bad7a423338a";

    // Obstacle
    std::string scan_topic = "scan_se";
    std::string robot_frame = "laser_se";
    std::string map_frame = "map";
    double slow_zone1_min = 1.5;
    double slow_zone1_max = 2.0;
    double slow_zone2_min = 1.0;
    double slow_zone2_max = 1.5;
    double stop_zone_max = 1.0;
    double main_lane_width = 1.2;
    double side_lane_width = 0.0; // 需确认
    double lane_detect_length = 0.6;
    int min_points_threshold = 5;
    double right_turn_distance = 80.0;
    double left_turn_distance = 80.0;
    double vehicle_length = 90.0;
    double vehicle_width = 75.0;

    // Audio
    std::string port = "/dev/ttys5";
    int baudrate = 9600;
    int slave_id = 1; // 需确认

    // MQTT
    std::string mqtt_broker = "";
    std::string agv_id = "1";
    std::string mqtt_username = "";
    std::string mqtt_password = "";
    double status_report_interval = 1.0;
    int max_retry_attempts = 3;
    double retry_delay_seconds = 1.0;
    std::string db_host = "localhost:3306";
    std::string db_user = "root";
    std::string db_password = "root";
    std::string db_name = "db_ant";
};

class ParameterServer : public rclcpp::Node {
public:
    ParameterServer() : Node("parameter_server") {
        declare_parameter<std::string>("db_host", "localhost");
        declare_parameter<std::string>("db_user", "root");
        declare_parameter<std::string>("db_password", "root");
        declare_parameter<std::string>("db_name", "db_ant");
        declare_parameter<std::string>("mapping_file", "node_parameter_mapping.yaml");
        declare_parameter<std::string>("metadata_file", "parameter_metadata.yaml");
        
        db_host_ = this->get_parameter("db_host").as_string();
        db_user_ = this->get_parameter("db_user").as_string();
        db_password_ = this->get_parameter("db_password").as_string();
        db_name_ = this->get_parameter("db_name").as_string();

        // 1. 初始化参数处理器映射表 (核心优化：消除 if/else)
        initParamHandlers();

        // 2. 加载默认映射配置
        loadNodeMapping();

        // 3. 创建服务
        update_srv_ = create_service<std_srvs::srv::Trigger>(
            "~/update_parameters",
            std::bind(&ParameterServer::updateParameters, this, std::placeholders::_1, std::placeholders::_2));

        RCLCPP_INFO(get_logger(), "Parameter Server Initialized with Dynamic Mapping.");
        
        update_sub_ = this->create_subscription<std_msgs::msg::UInt8>(
            "parameter_update_topic",
            10,
            std::bind(&ParameterServer::topicUpdateCallback, this, std::placeholders::_1)
        );
        
        param_update_publisher_ = this->create_publisher<std_msgs::msg::UInt8>(
            "param_update", 10);
        
        // 初始尝试加载一次
        //updateParameters(nullptr, nullptr);
    }

private:
    // 参数处理器：封装了获取和设置特定类型参数的能力
    struct ParamHandler {
        std::string name;
        std::string type; // "double", "int", "bool", "string"
        
        // Getters
        std::function<double()> get_double;
        std::function<int()> get_int;
        std::function<bool()> get_bool;
        std::function<std::string()> get_string;

        // Setters
        std::function<void(double)> set_double;
        std::function<void(int)> set_int;
        std::function<void(bool)> set_bool;
        std::function<void(const std::string&)> set_string;
    };
    
    std::shared_ptr<sql::Connection> con_; 
    
    std::string db_host_;
    std::string db_user_;
    std::string db_password_;
    std::string db_name_;

    ParameterSet cached_params_;
    std::map<std::string, ParamHandler> handlers_;
    
    // 存储节点映射信息
    struct NodeMapping {
        std::vector<std::string> params;
        std::string yaml_file;
    };
    std::map<std::string, NodeMapping> node_mappings_;

    rclcpp::Service<std_srvs::srv::Trigger>::SharedPtr update_srv_;
    std::map<std::string, rclcpp::Client<rcl_interfaces::srv::SetParameters>::SharedPtr> node_clients_;
    rclcpp::Subscription<std_msgs::msg::UInt8>::SharedPtr update_sub_;
    rclcpp::Publisher<std_msgs::msg::UInt8>::SharedPtr param_update_publisher_;
    bool params_loaded = false;
    
    void publishParamUpdate()
    {
        auto msg = std_msgs::msg::UInt8();
        msg.data = 1;  // 表示参数更新完成
        param_update_publisher_->publish(msg);
        RCLCPP_DEBUG(this->get_logger(), "Published param_update message");
    }

    // 初始化映射表：将字符串参数名绑定到结构体成员
    void initParamHandlers() {
        // 辅助 Lambda (复用之前的定义)
        auto reg_double = [this](const std::string& n, double ParameterSet::*m) {
            handlers_[n] = {n, "double", [this,m](){return cached_params_.*m;}, nullptr,nullptr,nullptr, [this,m](double v){cached_params_.*m=v;}, nullptr,nullptr,nullptr};
        };
        auto reg_string = [this](const std::string& n, std::string ParameterSet::*m) {
            handlers_[n] = {n, "string", nullptr,nullptr,nullptr,[this,m](){return cached_params_.*m;}, nullptr,nullptr,nullptr, [this,m](const std::string& v){cached_params_.*m=v;}};
        };
        auto reg_int = [this](const std::string& n, int ParameterSet::*m) {
            handlers_[n] = {n, "int", nullptr,[this,m](){return cached_params_.*m;},nullptr,nullptr, nullptr,[this,m](int v){cached_params_.*m=v;},nullptr,nullptr};
        };
    
        // --- 定位与姿态 ---
        reg_double("ref_latitude", &ParameterSet::ref_latitude);
        reg_double("ref_longitude", &ParameterSet::ref_longitude);
        reg_double("ref_height", &ParameterSet::ref_height);
        reg_double("gnss_offset_x", &ParameterSet::gnss_offset_x);
        reg_double("gnss_offset_y", &ParameterSet::gnss_offset_y);
        
        reg_string("map_path", &ParameterSet::map_path);
        reg_double("voxel_leaf_size", &ParameterSet::voxel_leaf_size);
        reg_double("scan_max_range", &ParameterSet::scan_max_range);
        reg_double("scan_min_range", &ParameterSet::scan_min_range);
    
        reg_string("target_frame", &ParameterSet::target_frame);
        reg_string("source_frame", &ParameterSet::source_frame);
        reg_double("publish_rate", &ParameterSet::publish_rate);
    
        // --- 导航与控制 ---
        reg_double("lookahead_dist", &ParameterSet::lookahead_dist);
        reg_double("max_velocity", &ParameterSet::max_velocity);
        reg_double("min_velocity", &ParameterSet::min_velocity);
        reg_double("xy_goal_tolerance", &ParameterSet::xy_goal_tolerance);
        reg_double("xy_middle_tolerance", &ParameterSet::xy_middle_tolerance);
        reg_double("yaw_goal_tolerance", &ParameterSet::yaw_goal_tolerance);
        reg_double("wheel_base", &ParameterSet::wheel_base);
        reg_double("angular_velocity_limit", &ParameterSet::angular_velocity_limit);
        reg_double("proportion", &ParameterSet::proportion);
        reg_string("authorized_hardware_id", &ParameterSet::authorized_hardware_id);
    
        // --- 感知与避障 ---
        reg_string("scan_topic", &ParameterSet::scan_topic);
        reg_string("robot_frame", &ParameterSet::robot_frame);
        reg_string("map_frame", &ParameterSet::map_frame);
        reg_double("slow_zone1_min", &ParameterSet::slow_zone1_min);
        reg_double("slow_zone1_max", &ParameterSet::slow_zone1_max);
        reg_double("slow_zone2_min", &ParameterSet::slow_zone2_min);
        reg_double("slow_zone2_max", &ParameterSet::slow_zone2_max);
        reg_double("stop_zone_max", &ParameterSet::stop_zone_max);
        reg_double("main_lane_width", &ParameterSet::main_lane_width);
        reg_double("side_lane_width", &ParameterSet::side_lane_width);
        reg_double("lane_detect_length", &ParameterSet::lane_detect_length);
        reg_int("min_points_threshold", &ParameterSet::min_points_threshold);
        reg_double("right_turn_distance", &ParameterSet::right_turn_distance);
        reg_double("left_turn_distance", &ParameterSet::left_turn_distance);
        reg_double("vehicle_length", &ParameterSet::vehicle_length);
        reg_double("vehicle_width", &ParameterSet::vehicle_width);
    
        // --- 硬件驱动 ---
        reg_string("port", &ParameterSet::port);
        reg_int("baudrate", &ParameterSet::baudrate);
        reg_int("slave_id", &ParameterSet::slave_id);
    
        // --- 通信与数据存储 ---
        reg_string("mqtt_broker", &ParameterSet::mqtt_broker);
        reg_string("agv_id", &ParameterSet::agv_id);
        reg_string("mqtt_username", &ParameterSet::mqtt_username);
        reg_string("mqtt_password", &ParameterSet::mqtt_password);
        reg_double("status_report_interval", &ParameterSet::status_report_interval);
        reg_int("max_retry_attempts", &ParameterSet::max_retry_attempts); // 调整为int
        reg_double("retry_delay_seconds", &ParameterSet::retry_delay_seconds);
        reg_string("db_host", &ParameterSet::db_host);
        reg_string("db_user", &ParameterSet::db_user);
        reg_string("db_password", &ParameterSet::db_password);
        reg_string("db_name", &ParameterSet::db_name);
    }

    void loadNodeMapping() {
        std::string mapping_file = get_parameter("mapping_file").as_string();
        try {
            YAML::Node config = YAML::LoadFile(mapping_file);
            for (const auto& node : config) {
                std::string node_name = node.first.as<std::string>();
                NodeMapping mapping;
                
                if (node.second["params"]) {
                    for (const auto& p : node.second["params"]) {
                        mapping.params.push_back(p.as<std::string>());
                    }
                }
                if (node.second["yaml_file"]) {
                    mapping.yaml_file = node.second["yaml_file"].as<std::string>();
                }
                node_mappings_[node_name] = mapping;
                
                // 初始化客户端
                if (node_clients_.find(node_name) == node_clients_.end()) {
                    node_clients_[node_name] = create_client<rcl_interfaces::srv::SetParameters>(node_name + "/set_parameters");
                }
            }
            RCLCPP_INFO(get_logger(), "Loaded mappings for %zu nodes.", node_mappings_.size());
        } catch (const std::exception& e) {
            RCLCPP_ERROR(get_logger(), "Failed to load mapping file: %s", e.what());
        }
    }

    void updateParameters(
        const std::shared_ptr<std_srvs::srv::Trigger::Request> req,
        std::shared_ptr<std_srvs::srv::Trigger::Response> res)
    {
        RCLCPP_INFO(get_logger(), "Triggering parameter update from database...");
        
        try {
            Driver* driver = sql::mysql::get_mysql_driver_instance();
            if (!con_ || !con_->isValid()) {
                std::string url = "tcp://" + db_host_ + ":3306";
                con_.reset(driver->connect(url, db_user_, db_password_));
                con_->setSchema(db_name_);
            }
    
            auto stmt_deleter = [](sql::Statement* s) {
                if (s) { try { s->close(); } catch (...) {} delete s; }
            };
            auto res_deleter = [](sql::ResultSet* r) {
                if (r) { try { r->close(); } catch (...) {} delete r; }
            };
    
            std::shared_ptr<sql::Statement> stmt(con_->createStatement(), stmt_deleter);
            std::string query = "SELECT param_id, value FROM t_parameter WHERE isDelete = 0 AND visible = 1";
            std::shared_ptr<sql::ResultSet> db_result(stmt->executeQuery(query), res_deleter);
    
            int count = 0;
            while (db_result->next()) {
                std::string param_id = db_result->getString("param_id");
                std::string value_str = db_result->getString("value");
    
                if (handlers_.find(param_id) != handlers_.end()) {
                    auto& handler = handlers_[param_id];
                    try {
                        if (handler.type == "double") {
                            handler.set_double(std::stod(value_str));
                        } else if (handler.type == "int") {
                            handler.set_int(std::stoi(value_str));
                        } else if (handler.type == "bool") {
                            handler.set_bool(value_str == "1" || value_str == "true");
                        } else if (handler.type == "string") {
                            handler.set_string(value_str);
                        }
                        count++;
                    } catch (...) {
                        RCLCPP_WARN(get_logger(), "Convert failed for %s", param_id.c_str());
                    }
                }
            }
    
            RCLCPP_INFO(get_logger(), "Updated %d params from database.", count);
            res->success = true;
            res->message = "Parameters loaded successfully.";
    
            // ✅ 关键：标记参数已加载，并触发分发与文件更新
            params_loaded = true;
            distributeParameters();      // 分发到各 ROS 2 节点
            publishParamUpdate();
            updateAllYamlFiles();        // 更新 YAML 配置文件
    
        } catch (const SQLException &e) {
            RCLCPP_ERROR(get_logger(), "MySQL error: %s", e.what());
            res->success = false;
            res->message = "Database query failed.";
        } catch (const std::exception &e) {
            RCLCPP_ERROR(get_logger(), "Error: %s", e.what());
            res->success = false;
            res->message = "Unexpected error during parameter update.";
        } catch (...) {
            RCLCPP_ERROR(get_logger(), "Unknown exception in updateParameters.");
            res->success = false;
            res->message = "Unknown error.";
        }
    }
    
    // 【新增】话题回调实现
    void topicUpdateCallback(const std_msgs::msg::UInt8::SharedPtr msg)
    {
        // 可选：打印接收到的触发信号值
        RCLCPP_DEBUG(get_logger(), "Received trigger signal on topic: %d", msg->data);
    
        // 构造虚拟的请求和响应对象
        // 因为 updateParameters 需要这两个参数，但话题回调不需要它们
        auto req = std::make_shared<std_srvs::srv::Trigger::Request>();
        auto res = std::make_shared<std_srvs::srv::Trigger::Response>();
    
        // 调用核心更新逻辑
        // 注意：这里是在回调线程中执行，如果 updateParameters 耗时较长，可能会阻塞话题接收
        // 如果数据库查询很慢，建议将 updateParameters 放入独立线程或使用 CallbackGroup
        updateParameters(req, res);
    
        if (res->success) {
            RCLCPP_INFO(get_logger(), "Parameter update triggered successfully by topic message.");
        } else {
            RCLCPP_ERROR(get_logger(), "Parameter update failed via topic trigger: %s", res->message.c_str());
        }
    }
    
    void distributeParameters() {
        if (!params_loaded) {
            RCLCPP_WARN(get_logger(), "Cannot distribute: parameters not loaded yet.");
            return;
        }
    
        for (const auto& [node_name, mapping] : node_mappings_) {
            std::vector<rclcpp::Parameter> params_to_set;
            
            // 收集要发送的参数
            for (const auto& p_name : mapping.params) {
                if (handlers_.find(p_name) == handlers_.end()) {
                    RCLCPP_WARN(get_logger(), "Mapping refers to unknown parameter: %s", p_name.c_str());
                    continue;
                }
    
                auto& handler = handlers_[p_name];
                // 直接从 handler 获取值构建 rclcpp::Parameter，或者直接构建 msg
                // 这里我们构建 rclcpp::Parameter 以便复用下面的逻辑，或者直接操作 msg
                if (handler.type == "double") {
                    params_to_set.emplace_back(p_name, handler.get_double());
                } else if (handler.type == "int") {
                    params_to_set.emplace_back(p_name, handler.get_int());
                } else if (handler.type == "bool") {
                    params_to_set.emplace_back(p_name, handler.get_bool());
                } else if (handler.type == "string") {
                    params_to_set.emplace_back(p_name, handler.get_string());
                }
            }
    
            if (!params_to_set.empty() && node_clients_[node_name]->service_is_ready()) {
                auto request = std::make_shared<rcl_interfaces::srv::SetParameters::Request>();
                
                for (const auto& p : params_to_set) {
                    rcl_interfaces::msg::Parameter param_msg;
                    param_msg.name = p.get_name();
                    
                    // --- 核心修复：根据类型手动赋值 ---
                    auto type = p.get_type();
                    switch (type) {
                        case rclcpp::ParameterType::PARAMETER_BOOL:
                            param_msg.value.type = rcl_interfaces::msg::ParameterType::PARAMETER_BOOL;
                            param_msg.value.bool_value = p.as_bool();
                            break;
                        case rclcpp::ParameterType::PARAMETER_INTEGER:
                            param_msg.value.type = rcl_interfaces::msg::ParameterType::PARAMETER_INTEGER;
                            param_msg.value.integer_value = p.as_int();
                            break;
                        case rclcpp::ParameterType::PARAMETER_DOUBLE:
                            param_msg.value.type = rcl_interfaces::msg::ParameterType::PARAMETER_DOUBLE;
                            param_msg.value.double_value = p.as_double();
                            break;
                        case rclcpp::ParameterType::PARAMETER_STRING:
                            param_msg.value.type = rcl_interfaces::msg::ParameterType::PARAMETER_STRING;
                            param_msg.value.string_value = p.as_string();
                            break;
                        case rclcpp::ParameterType::PARAMETER_BYTE_ARRAY:
                        case rclcpp::ParameterType::PARAMETER_BOOL_ARRAY:
                        case rclcpp::ParameterType::PARAMETER_INTEGER_ARRAY:
                        case rclcpp::ParameterType::PARAMETER_DOUBLE_ARRAY:
                        case rclcpp::ParameterType::PARAMETER_STRING_ARRAY:
                            RCLCPP_WARN(get_logger(), "Array parameters not supported in this distribution logic yet: %s", p.get_name().c_str());
                            continue; 
                        case rclcpp::ParameterType::PARAMETER_NOT_SET:
                        default:
                            continue;
                    }
                    
                    request->parameters.push_back(param_msg);
                }
                
                // 异步发送请求
                node_clients_[node_name]->async_send_request(
                    request, 
                    [node_name](rclcpp::Client<rcl_interfaces::srv::SetParameters>::SharedFuture future) {
                        auto result = future.get();
                        if (result) {
                            bool all_success = true;
                            for (const auto& res : result->results) {
                                if (!res.successful) {
                                    all_success = false;
                                    RCLCPP_WARN(rclcpp::get_logger("parameter_server"), 
                                        "Failed to set param on %s: %s", node_name.c_str(), res.reason.c_str());
                                }
                            }
                            if (all_success) {
                                RCLCPP_DEBUG(rclcpp::get_logger("parameter_server"), "Successfully updated parameters for %s", node_name.c_str());
                            }
                        } else {
                            RCLCPP_ERROR(rclcpp::get_logger("parameter_server"), "Service call failed for %s", node_name.c_str());
                        }
                    });
            }
        }
    }

    void updateAllYamlFiles()
    {
        if (!params_loaded) return;
    
        for (const auto& [node_name, mapping] : node_mappings_) {
            if (mapping.yaml_file.empty()) continue;
    
            YAML::Node config;
            try {
                config = YAML::LoadFile(mapping.yaml_file);
            } catch (...) {
                RCLCPP_WARN(get_logger(), "YAML file not found or invalid, creating new: %s", mapping.yaml_file.c_str());
                config = YAML::Node(YAML::NodeType::Map);
            }
    
            // 确保 node_name 和 ros__parameters 存在
            if (!config[node_name]) {
                config[node_name] = YAML::Node(YAML::NodeType::Map);
            }
            if (!config[node_name]["ros__parameters"]) {
                config[node_name]["ros__parameters"] = YAML::Node(YAML::NodeType::Map);
            }
    
            YAML::Node param_node = config[node_name]["ros__parameters"];
    
            bool updated = false;
            for (const auto& p_name : mapping.params) {
                if (handlers_.find(p_name) == handlers_.end()) continue;
                auto& handler = handlers_[p_name];
    
                try {
                    if (handler.type == "double") {
                        double val = handler.get_double();
                        // 避免浮点精度问题：保留6位小数
                        param_node[p_name] = val; 
                    } else if (handler.type == "int") {
                        param_node[p_name] = handler.get_int();
                    } else if (handler.type == "bool") {
                        param_node[p_name] = handler.get_bool();
                    } else if (handler.type == "string") {
                        param_node[p_name] = handler.get_string();
                    }
                    updated = true;
                } catch (const std::exception& e) {
                    RCLCPP_ERROR(get_logger(), "Failed to set %s in YAML: %s", p_name.c_str(), e.what());
                }
            }
    
            if (updated) {
                try {
                    std::ofstream fout(mapping.yaml_file);
                    if (!fout.is_open()) {
                        RCLCPP_ERROR(get_logger(), "Cannot open YAML for writing: %s", mapping.yaml_file.c_str());
                        continue;
                    }
                    fout << config;
                    fout.close();
                    RCLCPP_INFO(get_logger(), "Successfully updated YAML file: %s", mapping.yaml_file.c_str());
                } catch (const std::exception& e) {
                    RCLCPP_ERROR(get_logger(), "Failed to write YAML %s: %s", mapping.yaml_file.c_str(), e.what());
                }
            }
        }
    }
};

int main(int argc, char * argv[]) {
    rclcpp::init(argc, argv);
    rclcpp::spin(std::make_shared<ParameterServer>());
    rclcpp::shutdown();
    return 0;
}