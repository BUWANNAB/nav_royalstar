// factor_graph_builder.h
#pragma once
#include <gtsam/nonlinear/NonlinearFactorGraph.h>
#include <gtsam/nonlinear/Values.h>
#include <gtsam/geometry/Pose3.h>
#include <gtsam/slam/PriorFactor.h>
#include <gtsam/slam/BetweenFactor.h>
#include <gtsam/navigation/GPSFactor.h>

class FactorGraphBuilder {
public:
  FactorGraphBuilder() {
    clear();
  }
  
  void addPriorFactor(gtsam::Key key, const gtsam::Pose3& prior, 
                     const gtsam::SharedNoiseModel& noise_model) {
    if (!noise_model) {
      throw std::runtime_error("Noise model is null in addPriorFactor!");
    }
    graph_.add(gtsam::PriorFactor<gtsam::Pose3>(key, prior, noise_model));
  }
  
  void addGnssFactor(gtsam::Key key, const gtsam::Pose3& gnss_measurement,
                    const gtsam::SharedNoiseModel& noise_model) {
    if (!noise_model) {
      throw std::runtime_error("Noise model is null in addGnssFactor!");
    }
    graph_.add(gtsam::PriorFactor<gtsam::Pose3>(key, gnss_measurement, noise_model));
  }
  
  void addLidarFactor(gtsam::Key key, const gtsam::Pose3& lidar_measurement,
                      const gtsam::SharedNoiseModel& noise_model) {
    if (!noise_model) {
      throw std::runtime_error("Noise model is null in addLidarFactor!");
    }
    graph_.add(gtsam::PriorFactor<gtsam::Pose3>(key, lidar_measurement, noise_model));
  }
  
  void addOdometryFactor(gtsam::Key prev_key, gtsam::Key curr_key, 
                        const gtsam::Pose3& rel_pose, 
                        const gtsam::SharedNoiseModel& noise_model) {
    if (!noise_model) {
      throw std::runtime_error("Noise model is null in addOdometryFactor!");
    }
    if (prev_key >= curr_key) {
      throw std::runtime_error("Invalid key sequence in odometry factor");
    }
    graph_.add(gtsam::BetweenFactor<gtsam::Pose3>(prev_key, curr_key, rel_pose, noise_model));
  }
  
  void setInitialEstimate(gtsam::Key key, const gtsam::Pose3& pose) {
    // 检查是否已存在该key的初始值，如果存在则更新
    if (initial_estimate_.exists(key)) {
      initial_estimate_.update(key, pose);
    } else {
      initial_estimate_.insert(key, pose);
    }
  }
  
  const gtsam::NonlinearFactorGraph& getFactors() const { return graph_; }
  const gtsam::Values& getInitialEstimate() const { return initial_estimate_; }
  
  // 新增方法：获取新增的初始估计（只包含本次更新的变量）
  gtsam::Values getNewInitialEstimate() const {
    return initial_estimate_;
  }
  
  void clear() { 
    graph_.resize(0); 
    initial_estimate_.clear();
  }

private:
  gtsam::NonlinearFactorGraph graph_;
  gtsam::Values initial_estimate_;
};
