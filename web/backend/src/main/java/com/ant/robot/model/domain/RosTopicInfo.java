package com.ant.robot.model.domain;

import org.json.JSONObject;

/**
 * ROS 话题信息封装类
 */
public class RosTopicInfo {
    private final String topic;
    private final JSONObject message;

    public RosTopicInfo(String topic, JSONObject message) {
        this.topic = topic;
        this.message = message;
    }

    public String getTopic() {
        return topic;
    }

    public JSONObject getMessage() {
        return message;
    }
}