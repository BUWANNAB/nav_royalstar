// pose_optimizer.h
#pragma once
#include <gtsam/nonlinear/ISAM2.h>
#include <gtsam/nonlinear/Values.h>
#include <iostream>
#include <rclcpp/rclcpp.hpp>

class PoseOptimizer {
public:
  PoseOptimizer() {
    // 配置ISAM2参数[10](@ref)
    gtsam::ISAM2Params parameters;
    parameters.relinearizeThreshold = 0.1;
    parameters.relinearizeSkip = 1;
    parameters.enableDetailedResults = true;
    parameters.cacheLinearizedFactors = false;
    parameters.factorization = gtsam::ISAM2Params::Factorization::QR;
    isam2_ = std::make_unique<gtsam::ISAM2>(parameters);
    
    RCLCPP_INFO(rclcpp::get_logger("PoseOptimizer"), 
               "PoseOptimizer initialized with ISAM2");
  }
  
  // 更新方法（保持向后兼容）
  gtsam::Values update(const gtsam::NonlinearFactorGraph& new_factors, 
                      const gtsam::Values& new_initial_estimate) {
    try {
      if (new_factors.size() == 0 && new_initial_estimate.size() == 0) {
        return isam2_->calculateBestEstimate();
      }
      
      RCLCPP_INFO(rclcpp::get_logger("PoseOptimizer"), 
                 "Updating with %lu new factors and %lu new values", 
                 new_factors.size(), new_initial_estimate.size());
      
      isam2_->update(new_factors, new_initial_estimate);
      gtsam::Values optimized_values = isam2_->calculateBestEstimate();
      
      RCLCPP_INFO(rclcpp::get_logger("PoseOptimizer"), 
                 "Optimization completed. Values size: %lu", optimized_values.size());
      
      return optimized_values;
      
    } catch (const std::exception& e) {
      RCLCPP_ERROR(rclcpp::get_logger("PoseOptimizer"), 
                  "Error in update: %s", e.what());
      throw;
    }
  }

  // 新增方法：获取ISAM2实例（用于滑动窗口管理器）
  std::unique_ptr<gtsam::ISAM2>& getISAM2() { return isam2_; }

private:
  std::unique_ptr<gtsam::ISAM2> isam2_;
};
