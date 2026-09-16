package com.ant.robot.model.domain;

import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONArray;

/**
 * 点发送任务状态管理类
 * 用于保存和恢复点发送任务的进度状态
 */
public class PointTaskState {
    private final JSONObject jsonObject;
    private int currentIndex;    // 当前发送到的点索引
    private int pointsendnum;    // 当前发送点计数
    private final int totalPoints; // 总点数
    private int coilRequest;     // 线圈请求状态

    /**
     * 构造函数
     * @param jsonObject 包含路径点数据的JSON对象
     */
    public PointTaskState(JSONObject jsonObject) throws JSONException {
        this.jsonObject = jsonObject;
        this.currentIndex = 1; // 从第二个点开始(第一个是起始点)
        this.pointsendnum = 1;
        JSONArray points = jsonObject.getJSONArray("points");
        this.totalPoints = ((points.length() - 1) / 9) + 1;
        this.coilRequest = 0;
    }

    // Getters and Setters

    public JSONObject getJsonObject() {
        return jsonObject;
    }

    public int getCurrentIndex() {
        return currentIndex;
    }

    public void setCurrentIndex(int currentIndex) {
        this.currentIndex = currentIndex;
    }

    public int getPointsendnum() {
        return pointsendnum;
    }

    public void setPointsendnum(int pointsendnum) {
        this.pointsendnum = pointsendnum;
    }

    public int getTotalPoints() {
        return totalPoints;
    }

    public int getCoilRequest() {
        return coilRequest;
    }

    public void setCoilRequest(int coilRequest) {
        this.coilRequest = coilRequest;
    }

    /**
     * 移动到下一个点
     */
    public void moveToNextPoint() {
        this.currentIndex += 9;
        this.pointsendnum++;
    }

    /**
     * 重置线圈状态
     */
    public void resetCoilRequest() {
        this.coilRequest = 0;
    }

    /**
     * 获取当前点的JSON数据
     * @return 当前点的JSON数据
     */
    public JSONArray getCurrentPointData() throws JSONException {
        return jsonObject.getJSONArray("points");
    }

    /**
     * 检查是否还有更多点需要发送
     * @return 如果还有更多点返回true，否则返回false
     */
    public boolean hasMorePoints() throws JSONException {
        return currentIndex < jsonObject.getJSONArray("points").length();
    }

    /**
     * 获取起始点速度
     * @return 起始点速度
     */
    public float getStartingSpeed() throws JSONException {
        return (float) jsonObject.getJSONArray("points").getDouble(4);
    }
}