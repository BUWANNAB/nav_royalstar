package com.ant.robot.service;

import org.springframework.stereotype.Service;

@Service
public class LocationService {

    private double lon;
    private double lat;

    // 设置经纬度的方法
    public void setCoordinates(double lon, double lat) {
        this.lon = lon;
        this.lat = lat;
    }

    // 获取当前经度
    public double getLon() {
        return lon;
    }

    // 获取当前纬度
    public double getLat() {
        return lat;
    }
}
