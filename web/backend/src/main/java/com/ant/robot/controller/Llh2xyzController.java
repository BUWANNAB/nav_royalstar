package com.ant.robot.controller;

import com.ant.robot.model.domain.Llh2xyz;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class Llh2xyzController {

    static double a = 6378137.0;// 长半轴（米）
    static double f = 1.0 / 298.257223563;// 扁率
    static double b = a * (1.0 - f);// 短半轴（米）
    static double e2 = 2 * f - f * f;// 第一偏心率平方
    static double epsilon = 1e-12;       // 迭代终止阈值

    // 将目标经纬度点转换为相对于局部坐标原点的相对x和y坐标
    public double[] convertToLocalCoordinates(double localLat, double localLon, double targetLat, double targetLon) {
        Llh2xyz orig = new Llh2xyz();//原点
        orig.lon = localLon;
        orig.lat = localLat;
        orig.height  = 0;
        Llh2xyz curr = new Llh2xyz();//目标点
        curr.lon = targetLon;
        curr.lat = targetLat;
        curr.height  = 4.21;
        // 偏心率
        Llh2xyz origloc = geodeticToCartesian(curr);
        Llh2xyz enuloc = ecefToEnu(orig, origloc);

        double dy = enuloc.north;
        double dx = enuloc.east;
        double dz = enuloc.up;

        return new double[]{dx, dy,dz};
    }

    // ECEF转ENU（东北天）坐标系
    public Llh2xyz ecefToEnu(Llh2xyz ref, Llh2xyz target){
        Llh2xyz ref_;
        Llh2xyz d = new Llh2xyz();
        double lat_rad;
        double lon_rad;
        Llh2xyz temp = new Llh2xyz();
        double SIN_lon_rad;
        double SIN_lat_rad;
        double COS_lat_rad;
        double COS_lon_rad;

        // 参考点ECEF坐标
        ref_ = geodeticToCartesian(ref);

        // 计算相对位移
        d.x = target.x - ref_.x;
        d.y = target.y - ref_.y;
        d.z = target.z - ref_.z;
        // 转换为弧度
        lat_rad = ref.lat * Math.PI / 180.0;
        lon_rad = ref.lon * Math.PI / 180.0;

        SIN_lon_rad = Math.sin(lon_rad);
        SIN_lat_rad = Math.sin(lat_rad);
        COS_lat_rad = Math.cos(lat_rad);
        COS_lon_rad = Math.cos(lon_rad);

        // ENU转换矩阵（方向余弦矩阵）
        temp.east  = -SIN_lon_rad * d.x + COS_lon_rad * d.y;
        temp.north = -SIN_lat_rad * COS_lon_rad * d.x - SIN_lat_rad * SIN_lon_rad * d.y + COS_lat_rad * d.z;
        temp.up    =  COS_lat_rad * COS_lon_rad * d.x + COS_lat_rad * SIN_lon_rad * d.y + SIN_lat_rad * d.z;

        return temp;
    }
    // 大地坐标转ECEF直角坐标
    public Llh2xyz geodeticToCartesian(Llh2xyz llh2xyz){
        Llh2xyz loc = new Llh2xyz();
        double lat_rad;
        double lon_rad;
        double N;

        lat_rad = llh2xyz.lat * Math.PI / 180.0;
        lon_rad = llh2xyz.lon * Math.PI / 180.0;
        N =  a / Math.sqrt(1 - e2 * Math.pow(Math.sin(lat_rad), 2));

        loc.x = (N + llh2xyz.height) * Math.cos(lat_rad) * Math.cos(lon_rad);
        loc.y = (N + llh2xyz.height) * Math.cos(lat_rad) * Math.sin(lon_rad);
        loc.z = (N * (1 - e2) + llh2xyz.height) * Math.sin(lat_rad);

        return loc;
    }
    // 将目xy反解为经纬度
    public double[] convertFromLocalCoordinates(double localLat, double localLon, double dx, double dy , double dz) {
        double north = dy;
        double east = dx;
        double up = dz;
        Llh2xyz ref = new Llh2xyz();

        ref.lat = localLat;
        ref.lon = localLon;
        ref.height = 0;
        Llh2xyz llh2xyz = enuToGeodetic(ref,east,north,up);

        // 转换为度数并返回结果
        return new double[]{
                llh2xyz.lat,
                llh2xyz.lon
        };
    }

    // ENU（东北天）坐标系转ECEF再转大地坐标
    public Llh2xyz enuToGeodetic(Llh2xyz ref, double east, double north, double up) {
        // 1. 将参考点从大地坐标转换为ECEF坐标
        Llh2xyz refEcef = geodeticToCartesian(ref);

        // 2. 将ENU坐标转换为ECEF坐标
        Llh2xyz targetEcef = enuToEcef(ref, refEcef, east, north, up);

        // 3. 将ECEF坐标转换为大地坐标
        return cartesianToGeodetic(targetEcef);
    }

    // ENU坐标转ECEF坐标
    private Llh2xyz enuToEcef(Llh2xyz ref, Llh2xyz refEcef, double east, double north, double up) {
        // 转换为弧度
        double latRad = ref.lat * Math.PI / 180.0;
        double lonRad = ref.lon * Math.PI / 180.0;

        double N_ref =  a / Math.sqrt(1 - e2 * Math.pow(Math.sin(latRad), 2));

        double sinLat = Math.sin(latRad);
        double cosLat = Math.cos(latRad);
        double sinLon = Math.sin(lonRad);
        double cosLon = Math.cos(lonRad);

        // ENU到ECEF的转换矩阵（方向余弦矩阵的转置）
        double dx = -sinLon * east - sinLat * cosLon * north + cosLat * cosLon * up;
        double dy = cosLon * east - sinLat * sinLon * north + cosLat * sinLon * up;
        double dz = cosLat * north + sinLat * up;

        // 计算目标点ECEF坐标
        Llh2xyz target = new Llh2xyz();
        target.x = refEcef.x + dx;
        target.y = refEcef.y + dy;
        target.z = refEcef.z + dz;

        return target;
    }

    // ECEF直角坐标转大地坐标
    public Llh2xyz cartesianToGeodetic(Llh2xyz ecef) {
        Llh2xyz llh = new Llh2xyz();

        double x = ecef.x;
        double y = ecef.y;
        double z = ecef.z;

        double p = Math.sqrt(x * x + y * y);
        double theta = Math.atan2(z, p*(1 - e2));

        double phi_prev;
        int iter = 0;
        do {
            phi_prev = theta;
            double sin_phi = Math.sin(theta);
            double N = a / Math.sqrt(1 - e2 * sin_phi * sin_phi);
            theta = Math.atan2(z + e2 * N * sin_phi, p);
            iter++;
        } while (Math.abs(theta - phi_prev) > epsilon && iter < 100);

        double sin_phi = Math.sin(theta);
        double N = a / Math.sqrt(1 - e2 * sin_phi * sin_phi);

        // 经度计算
        double lonRad = Math.atan2(y, x);

        // 高度计算
        double height = p / Math.cos(theta) - N;

        // 转换为度
        llh.lat = theta * 180.0 / Math.PI;
        llh.lon = lonRad * 180.0 / Math.PI;
        llh.height = height;

        return llh;
    }




}
