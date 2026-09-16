package com.ant.robot.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "ros2.enabled", havingValue = "true")
public class ROS2Config {
    // 移除ros2CommunicationService()方法，因为已经有@Service注解
}