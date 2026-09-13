// sliding_window_manager.h
#pragma once  // 防止头文件重复包含

// GTSAM相关头文件
#include <gtsam/geometry/Pose3.h>           // GTSAM位姿表示
#include <gtsam/nonlinear/ISAM2.h>          // 增量平滑与建图优化器
#include <gtsam/nonlinear/Values.h>         // 优化变量容器
#include <gtsam/slam/BetweenFactor.h>       // 相对位姿约束因子

// STL容器
#include <deque>                            // 双端队列，用于关键帧管理
#include <unordered_set>                    // 哈希集合，用于快速查找
#include <rclcpp/rclcpp.hpp>                // ROS2客户端库

/**
 * @brief 滑动窗口管理器类 - 用于管理SLAM中的滑动窗口优化
 * 
 * 该类基于GTSAM的ISAM2优化器，实现了一个固定大小的滑动窗口机制，
 * 当窗口满时自动边缘化最旧的关键帧以维持计算复杂度恒定[6](@ref)。
 */
class SlidingWindowManager {
public:
  /**
   * @brief 窗口状态结构体，描述当前滑动窗口的状态信息
   */
  struct WindowState {
    std::deque<uint64_t> key_queue;           // 按时间排序的关键帧队列（先进先出）
    std::unordered_set<uint64_t> marginalizable_keys; // 可被边缘化的关键帧集合
    uint64_t current_key;                     // 当前正在处理的关键帧ID
    size_t window_size;                       // 窗口最大容量
    size_t current_size;                      // 当前窗口内关键帧数量
  };
  
  /**
   * @brief 构造函数，初始化滑动窗口管理器
   * @param window_size 滑动窗口大小，默认10个关键帧
   * 
   * 初始化ISAM2优化器参数并创建优化器实例，配置重线性化阈值、因子分解方法等[6](@ref)。
   */
  SlidingWindowManager(size_t window_size = 10) : window_size_(window_size) {
    // 初始化ISAM2参数
    gtsam::ISAM2Params parameters;
    parameters.relinearizeThreshold = 0.1;    // 重线性化阈值，控制线性化频率
    parameters.relinearizeSkip = 1;           // 重线性化跳过次数
    parameters.enableDetailedResults = true;  // 启用详细优化结果输出
    parameters.cacheLinearizedFactors = false; // 不缓存线性化因子，节省内存
    parameters.factorization = gtsam::ISAM2Params::Factorization::QR; // QR分解方法
    parameters.enablePartialRelinearizationCheck = false; // 禁用部分重线性化检查
    
    // 创建ISAM2优化器实例
    isam2_ = std::make_unique<gtsam::ISAM2>(parameters);
    
    // 输出初始化日志信息
    RCLCPP_INFO(rclcpp::get_logger("SlidingWindowManager"), 
                "SlidingWindowManager initialized with window size: %zu", window_size_);
  }
  
  /**
   * @brief 添加新位姿到滑动窗口
   * @param new_key 新关键帧的唯一标识符
   * @param new_factors 新添加的因子图约束
   * @param new_values 新变量的初始估计值
   * @return WindowState 返回当前窗口状态信息
   * 
   * 该方法处理新关键帧的添加，包括窗口管理、因子图更新和边缘化操作[6](@ref)。
   */
  WindowState addNewPose(uint64_t new_key, 
                        const gtsam::NonlinearFactorGraph& new_factors,
                        const gtsam::Values& new_values) {
    WindowState state;
    state.current_key = new_key;
    state.window_size = window_size_;
    
    try {
      // 将新关键帧添加到队列尾部
      key_queue_.push_back(new_key);
      state.current_size = key_queue_.size();
      
      // 检查窗口是否已满，需要边缘化旧关键帧
      if (key_queue_.size() > window_size_) {
        uint64_t old_key = key_queue_.front();  // 获取队列中最旧的关键帧
        marginalizable_keys_.insert(old_key);    // 标记为可边缘化
        state.marginalizable_keys = marginalizable_keys_;
        
        RCLCPP_DEBUG(rclcpp::get_logger("SlidingWindowManager"),
                    "Window full, marking key %lu for marginalization", old_key);
        
        key_queue_.pop_front();                 // 从队列中移除最旧关键帧
        state.current_size = key_queue_.size(); // 更新当前窗口大小
      }
      
      // 更新ISAM2优化器：添加新因子和初始值
      gtsam::ISAM2Result result = isam2_->update(new_factors, new_values);
      
      // 执行边缘化操作（如果有待边缘化的关键帧）
      if (!marginalizable_keys_.empty()) {
        marginalizeOldKeys();
      }
      
      // 输出调试信息
      RCLCPP_DEBUG(rclcpp::get_logger("SlidingWindowManager"),
                  "Added pose key: %lu, window size: %zu/%zu", 
                  new_key, state.current_size, window_size_);
                  
    } catch (const std::exception& e) {
      // 异常处理：记录错误日志并重新抛出异常
      RCLCPP_ERROR(rclcpp::get_logger("SlidingWindowManager"),
                  "Error in addNewPose: %s", e.what());
      throw;
    }
    
    return state;  // 返回当前窗口状态
  }
  
  /**
   * @brief 获取当前优化结果
   * @return gtsam::Values 包含所有优化变量当前估计值的容器
   */
  gtsam::Values getCurrentEstimate() {
    try {
      return isam2_->calculateBestEstimate();  // 计算并返回最优估计
    } catch (const std::exception& e) {
      RCLCPP_ERROR(rclcpp::get_logger("SlidingWindowManager"),
                  "Error in getCurrentEstimate: %s", e.what());
      return gtsam::Values();  // 返回空值对象
    }
  }
  
  /**
   * @brief 获取当前窗口中所有关键帧的标识符
   * @return std::vector<uint64_t> 关键帧标识符列表
   */
  std::vector<uint64_t> getWindowKeys() const {
    return std::vector<uint64_t>(key_queue_.begin(), key_queue_.end());
  }
  
  /// @brief 获取当前窗口中的关键帧数量
  size_t getCurrentWindowSize() const { return key_queue_.size(); }
  
  /// @brief 获取窗口最大容量
  size_t getMaxWindowSize() const { return window_size_; }
  
  /**
   * @brief 检查指定关键帧是否在当前窗口中
   * @param key 要检查的关键帧标识符
   * @return bool 如果存在返回true，否则false
   */
  bool isKeyInWindow(uint64_t key) const {
    return std::find(key_queue_.begin(), key_queue_.end(), key) != key_queue_.end();
  }

private:
  /**
   * @brief 边缘化旧关键帧的私有方法
   * 
   * 将标记为可边缘化的关键帧从因子图中移除，减少优化问题规模[6](@ref)。
   */
  void marginalizeOldKeys() {
    try {
      if (marginalizable_keys_.empty()) return;  // 如果没有可边缘化的key，直接返回
      
      // 方法1：使用FastList（兼容性更好的传统方法）
      gtsam::FastList<uint64_t> keys_to_remove(marginalizable_keys_.begin(), 
                                               marginalizable_keys_.end());
      
      // 方法2：或者直接使用KeyVector（新版本GTSAM推荐的方法）
      // gtsam::KeyVector keys_to_remove(marginalizable_keys_.begin(), 
      //                                marginalizable_keys_.end());
      
      RCLCPP_INFO(rclcpp::get_logger("SlidingWindowManager"),
                 "Marginalizing %zu old keys", keys_to_remove.size());
      
      // 执行边缘化操作：从因子图中移除指定关键帧
      isam2_->marginalizeLeaves(keys_to_remove);
      marginalizable_keys_.clear();  // 清空可边缘化关键帧集合
      
      RCLCPP_DEBUG(rclcpp::get_logger("SlidingWindowManager"),
                  "Marginalization completed successfully");
                  
    } catch (const std::exception& e) {
      // 边缘化失败时的错误处理
      RCLCPP_ERROR(rclcpp::get_logger("SlidingWindowManager"),
                  "Error in marginalizeOldKeys: %s", e.what());
      // 调用备用边缘化策略
      handleMarginalizationFallback();
    }
  }
  
  /**
   * @brief 边缘化失败时的备用处理策略
   * 
   * 当正式边缘化方法失败时，采用简单的清空策略作为回退方案。
   */
  void handleMarginalizationFallback() {
    RCLCPP_WARN(rclcpp::get_logger("SlidingWindowManager"),
               "Using fallback marginalization strategy");
    
    // 简化策略：直接清空可边缘化关键帧集合
    if (!marginalizable_keys_.empty()) {
      marginalizable_keys_.clear();
    }
  }
  
  // 成员变量
  std::unique_ptr<gtsam::ISAM2> isam2_;     // GTSAM增量优化器
  std::deque<uint64_t> key_queue_;           // 关键帧队列（按时间顺序）
  std::unordered_set<uint64_t> marginalizable_keys_; // 可边缘化关键帧集合
  size_t window_size_;                       // 滑动窗口最大容量
};
