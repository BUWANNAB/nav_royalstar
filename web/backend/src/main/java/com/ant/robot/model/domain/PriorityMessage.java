package com.ant.robot.model.domain;

import org.json.JSONObject;

public class PriorityMessage implements Comparable<PriorityMessage> {
    private final Object data; // 可以存储byte[]或JSONObject
    private final int priority; // 数值越小优先级越高(如0最高)
    private final long timestamp; // 用于同优先级消息的排序

    public PriorityMessage(byte[] data, int priority) {
        this.data = data;
        this.priority = priority;
        this.timestamp = System.currentTimeMillis();
    }

    public PriorityMessage(JSONObject data, int priority) {
        this.data = data;
        this.priority = priority;
        this.timestamp = System.currentTimeMillis();
    }

    @Override
    public int compareTo(PriorityMessage other) {
        int priorityCompare = Integer.compare(this.priority, other.priority);
        if (priorityCompare != 0) {
            return priorityCompare;
        }
        return Long.compare(this.timestamp, other.timestamp);
    }

    // Getters
    public Object getData() { return data; }
    public int getPriority() { return priority; }

    public boolean isJsonMessage() {
        return data instanceof JSONObject;
    }

    public boolean isBinaryMessage() {
        return data instanceof byte[];
    }
}