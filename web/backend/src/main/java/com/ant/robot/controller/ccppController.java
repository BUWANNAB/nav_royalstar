package com.ant.robot.controller;

import com.ant.robot.common.response.BaseResponse;
import com.ant.robot.common.response.ResultUtils;
import com.ant.robot.model.request.RouteRequest;
//import com.ant.robot.controller.Llh2xyzController;
import jakarta.annotation.Resource;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;



@RestController
@RequestMapping("ccpp")
@Slf4j
public class ccppController {

    @Resource
    private Llh2xyzController llh2xyzController;

    @PostMapping("/traverseRoute")
    public BaseResponse<List<Point>> traverseRoute(@RequestBody RouteRequest routeRequest) {
        List<Point> polygon = new ArrayList<>();
        RouteRequest.Points startPoint = routeRequest.getLocations().get(0);

        // 将经纬度处理为坐标
        for (RouteRequest.Points location : routeRequest.getLocations()) {
            double[] coordinates = llh2xyzController.convertToLocalCoordinates(
                    startPoint.getLatitude(), startPoint.getLongitude(),
                    location.getLatitude(), location.getLongitude()
            );
            // 从 double[] 中提取 x 和 y，并构造 Point 对象
            Point point = new Point(coordinates[0], coordinates[1]);

            polygon.add(point); // 添加 Point 对象到 polygon 列表
        }

        List<Point> newPolygon = new ArrayList<>();
        double stepSize = routeRequest.getStepSize();
        double offset = routeRequest.getOffset();
        int startIndex = routeRequest.getStartIndex();
        int nextIndex = routeRequest.getNextIndex();

        if (offset == 0) newPolygon = polygon;
        else {
            newPolygon = calculatePolygon(polygon,offset);
        }
        double slope = calculateSlope(polygon, startIndex,nextIndex);
        // 生成蛇形覆盖路径
        List<Point> path = boustrophedonCoverage(newPolygon, stepSize, slope,startIndex,nextIndex,offset);

        List<Point> finalPath = new ArrayList<>();
        path = removeDuplicates(path); // 去重处理

        for (Point point : path) {
            double[] latLon = llh2xyzController.convertFromLocalCoordinates(
                    startPoint.getLatitude(), startPoint.getLongitude(),
                    point.getLatitude(), point.getLongitude(),0
            );
            finalPath.add(new Point(latLon[0], latLon[1]));
        }

        // 返回生成的路径点列表
        return ResultUtils.success(finalPath);
    }

    @Data
    static class Point {
        private double latitude;
        private double longitude;

        public Point(double latitude,double longitude) {
            this.latitude = latitude;
            this.longitude = longitude;
        }

        boolean equals(Point other, double tolerance) {
            return Math.abs(this.latitude - other.latitude) < tolerance && Math.abs(this.longitude - other.longitude) < tolerance;
        }

        @Override
        public String toString() {
            return "Point(latitude=" + Math.round(latitude * 1e6) / 1e6 + ", longitude=" + Math.round(longitude * 1e6) / 1e6 + ")";
        }

    }

    private static class Line {
        double slope;
        double intercept;

        Line(double slope, double intercept) {
            this.slope = slope;
            this.intercept = intercept;
        }
    }

    public static List<Point> boustrophedonCoverage(List<Point> polygon, double stepSize, double slope, int startIndex,int nextIndex,double offset) {
        double tolerance = 1e-6;
        double c_start;
        Point startPoint = polygon.get(startIndex);
        Point nextPoint = polygon.get(nextIndex);

        if (Double.isInfinite(slope)) {
            c_start = startPoint.getLatitude();
        } else {
            c_start = startPoint.getLongitude() - slope * startPoint.getLatitude();
        }

        double minC, maxC;
        if (Double.isInfinite(slope)) {
            minC = polygon.stream().mapToDouble(Point::getLatitude).min().orElseThrow();
            maxC = polygon.stream().mapToDouble(Point::getLatitude).max().orElseThrow();
        } else {
            minC = Double.POSITIVE_INFINITY;
            maxC = Double.NEGATIVE_INFINITY;
            for (Point p : polygon) {
                double cVal = p.getLongitude() - slope * p.getLatitude();
                if (cVal < minC) minC = cVal;
                if (cVal > maxC) maxC = cVal;
            }
        }

        double deltaC = stepSize * (Double.isInfinite(slope) ? 1 : Math.sqrt(slope * slope + 1));
        double offsetDeltaC = offset * (Double.isInfinite(slope) ? 1 : Math.sqrt(slope * slope + 1));


        List<Point> list1 = getLineIntersections(polygon, slope, maxC - deltaC);
        List<Point> list2 = getLineIntersections(polygon, slope, minC + deltaC);


        List<Double> scanLines = new ArrayList<>();
        scanLines.add(c_start);

        double current = c_start - deltaC;
        while (current >= minC - tolerance) {
            List<Point> intersections = getLineIntersections(polygon, slope, current);
            if (!intersections.isEmpty()) {
                scanLines.add(current);
                System.out.println("Scan line C = " + current + ", Intersections: " + intersections);
                current -= deltaC;
            } else {
                break;
            }
        }

        current = c_start + deltaC;
        while (current <= maxC + tolerance) {
            List<Point> intersections = getLineIntersections(polygon, slope, current);
            if (!intersections.isEmpty()) {
                scanLines.add(current);
                System.out.println("Scan line C = " + current + ", Intersections: " + intersections);
                current += deltaC;
            } else {
                break;
            }
        }

        List<Point> path = new ArrayList<>();
        Point lastPoint = null;

        // 判断起始点和下一个点的顺序
        boolean reverseOrder = false; // 初始方向由起始点和下一个点决定
        if (slope == Double.POSITIVE_INFINITY) {
            if (startPoint.getLongitude() < nextPoint.getLongitude()) reverseOrder = true;
            else if (startPoint.getLongitude() > nextPoint.getLongitude()) reverseOrder = false;
        }else {
            if (startPoint.getLatitude() < nextPoint.getLatitude()) ;
            else if (startPoint.getLatitude() > nextPoint.getLatitude()) reverseOrder = true;
        }

        // 从起始点所在的扫描线开始生成路径
        for (int step = 0; step < scanLines.size(); step++) {
            double currentC = scanLines.get(step);
            List<Point> intersections = getLineIntersections(polygon, slope, currentC);
            // 输出起始点所在的扫描线的交点
            if (step == 0) {
                System.out.println("Start scan line C = " + currentC + ", Intersections: " + intersections);
            }

            intersections = removeDuplicates(intersections);
            System.out.println("intersections" + intersections);
            if (intersections.size() % 2 != 0) {
                if (Math.abs(currentC - minC) < tolerance || Math.abs(currentC - maxC) < tolerance) {
                    Point singlePoint = intersections.get(0);
                    if (lastPoint == null || !singlePoint.equals(lastPoint, tolerance)) {
                        path.add(singlePoint);
                        lastPoint = singlePoint;
                    }
                } else {
                    System.out.println("Warning: Odd intersections at C = " + currentC);
                }
                continue;
            }

            for (int i = 0; i < intersections.size(); i += 2) {
                Point start, end;
                if (reverseOrder) {
                    start = intersections.get(i + 1);
                    end = intersections.get(i);
                } else {
                    start = intersections.get(i);
                    end = intersections.get(i + 1);
                }
                if (lastPoint != null && !start.equals(lastPoint, tolerance)) {
                    path.add(lastPoint);
                    path.add(start);
                }
                path.add(start);
                path.add(end);
                lastPoint = end;
            }

            // 翻转路径方向
            reverseOrder = !reverseOrder;
        }

        return path;
    }

    private static List<Point> getLineIntersections(List<Point> polygon, double m, double c) {
        List<Point> intersections = new ArrayList<>();
        double tolerance = 1e-6;

        for (int i = 0; i < polygon.size(); i++) {
            Point p1 = polygon.get(i);
            Point p2 = polygon.get((i + 1) % polygon.size());

            if (Double.isInfinite(m)) { // 垂直扫描线
                double x = c;
                double minX = Math.min(p1.getLatitude(), p2.getLatitude());
                double maxX = Math.max(p1.getLatitude(), p2.getLatitude());
                if (Math.abs(p1.getLatitude() - x) < tolerance && Math.abs(p2.getLatitude() - x) < tolerance) {
                    // 如果重合，添加两个端点
                    intersections.add(new Point(p1.getLatitude(), p1.getLongitude()));
                    intersections.add(new Point(p2.getLatitude(), p2.getLongitude()));
                }else if (x >= minX - tolerance && x <= maxX + tolerance) {
                    intersections.add(new Point(x, p1.getLongitude()));
                }
            } else { // 非垂直扫描线
                if (Math.abs(p2.getLatitude() - p1.getLatitude()) < tolerance) { // 垂直边
                    double x = p1.getLatitude();
                    double y = m * x + c;
                    double minY = Math.min(p1.getLongitude(), p2.getLongitude());
                    double maxY = Math.max(p1.getLongitude(), p2.getLongitude());
                    if (y >= minY - tolerance && y <= maxY + tolerance) {
                        intersections.add(new Point(x, y));
                    }
                } else {
                    double lineM = (p2.getLongitude() - p1.getLongitude()) / (p2.getLatitude() - p1.getLatitude());
                    double lineC = p1.getLongitude() - lineM * p1.getLatitude();

                    if (Math.abs(lineM - m) > tolerance) {
                        double x = (c - lineC) / (lineM - m);
                        double y = m * x + c;
                        boolean xInRange = x >= Math.min(p1.getLatitude(), p2.getLatitude()) - tolerance
                                && x <= Math.max(p1.getLatitude(), p2.getLatitude()) + tolerance;
                        boolean yInRange = y >= Math.min(p1.getLongitude(), p2.getLongitude()) - tolerance
                                && y <= Math.max(p1.getLongitude(), p2.getLongitude()) + tolerance;
                        if (xInRange && yInRange) {
                            intersections.add(new Point(x, y));
                        }
                    } else { // 平行线，可能重合
                        if (Math.abs(lineC - c) < tolerance) { // 重合
                            double startX = p1.getLatitude();
                            double endX = p2.getLatitude();
                            intersections.add(new Point(startX, m * startX + c));
                            intersections.add(new Point(endX, m * endX + c));
                        }
                    }
                }
            }
        }

        intersections.sort((a, b) -> Double.compare(a.getLatitude(), b.getLatitude()));
        return intersections;
    }
    private static boolean pointsEqual(Point a, Point b) {
        return Math.abs(a.getLatitude() - b.getLatitude()) < 1e-6 && Math.abs(a.getLongitude() - b.getLongitude()) < 1e-6;
    }

    /**
     * 去重
     * @param path
     * @return
     */
    public static List<Point> removeDuplicates(List<Point> path) {
        List<Point> uniquePath = new ArrayList<>();
        for (Point point : path) {
            if (!uniquePath.stream().anyMatch(p -> pointsEqual(p, point))) {
                uniquePath.add(point);
            }
        }
        return uniquePath;
    }

    private static List<Point> calculatePolygon(List<Point> polygon, double offset) {
        List<Point> newPolygon = new ArrayList<>();
        int n = polygon.size();
        if (n < 3) return new ArrayList<>(polygon);

        // 判断多边形缠绕方向
        boolean isClockwise = isPolygonClockwise(polygon);

        List<Line> offsetLines = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Point p1 = polygon.get(i);
            Point p2 = polygon.get((i + 1) % n);

            // 计算边向量
            double dx = p2.getLongitude() - p1.getLongitude();
            double dy = p2.getLatitude() - p1.getLatitude();

            // 计算单位法向量（始终指向多边形外侧）
            double[] normal = calculateOutwardNormal(dx, dy, isClockwise);
            double nx = normal[0];
            double ny = normal[1];

            // 计算偏移后的平行线参数
            double offsetX = nx * offset;
            double offsetY = ny * offset;

            // 生成偏移后的边（两点式）
            Point offsetP1 = new Point(p1.getLatitude() + offsetY, p1.getLongitude() + offsetX);
            Point offsetP2 = new Point(p2.getLatitude() + offsetY, p2.getLongitude() + offsetX);

            // 转换为直线方程
            offsetLines.add(pointsToLine(offsetP1, offsetP2));
        }

        // 计算相邻偏移边的交点
        for (int i = 0; i < offsetLines.size(); i++) {
            Line prev = offsetLines.get((i + n - 1) % n);
            Line current = offsetLines.get(i);
            Point intersection = computeLineIntersection(prev, current);
            if (intersection != null) {
                newPolygon.add(intersection);
            }
        }

        return newPolygon;
    }

    // 判断多边形是否为顺时针方向
    private static boolean isPolygonClockwise(List<Point> polygon) {
        double area = 0;
        for (int i = 0; i < polygon.size(); i++) {
            Point p1 = polygon.get(i);
            Point p2 = polygon.get((i + 1) % polygon.size());
            area += (p2.getLongitude() - p1.getLongitude()) * (p2.getLatitude() + p1.getLatitude());
        }
        return area > 0;
    }

    // 计算外向单位法向量
    private static double[] calculateOutwardNormal(double dx, double dy, boolean isClockwise) {
        // 根据缠绕方向确定法向量方向
        double nx = isClockwise ? dy : -dy;
        double ny = isClockwise ? -dx : dx;

        double length = Math.sqrt(nx * nx + ny * ny);
        if (length < 1e-6) return new double[]{0, 0};
        return new double[]{nx/length, ny/length};
    }
    // 两点转直线方程
    private static Line pointsToLine(Point p1, Point p2) {
        double dx = p2.getLongitude() - p1.getLongitude();
        double dy = p2.getLatitude() - p1.getLatitude();

        if (Math.abs(dx) < 1e-6) { // 垂直线
            return new Line(Double.POSITIVE_INFINITY, p1.getLongitude());
        }

        double slope = dy / dx;
        double intercept = p1.getLatitude() - slope * p1.getLongitude();
        return new Line(slope, intercept);
    }
    private static Point computeLineIntersection(Line line1, Line line2) {
        double m1 = line1.slope;
        double c1 = line1.intercept;
        double m2 = line2.slope;
        double c2 = line2.intercept;

        if (Double.isInfinite(m1)) {
            if (Double.isInfinite(m2)) return null;
            double x = c1;
            double y = m2 * x + c2;
            return new Point(y, x);
        } else if (Double.isInfinite(m2)) {
            double x = c2;
            double y = m1 * x + c1;
            return new Point(y, x);
        } else if (Math.abs(m1 - m2) < 1e-6) {
            return null;
        } else {
            double x = (c2 - c1) / (m1 - m2);
            double y = m1 * x + c1;
            return new Point(y, x);
        }
    }

    private double calculateSlope(List<Point> points, int startIndex, int nextIndex) {
        // 计算斜率
        Point startPoint = points.get(startIndex);
        Point nextPoint = points.get(nextIndex);
        double dx = nextPoint.getLatitude() - startPoint.getLatitude();
        double dy = nextPoint.getLongitude() - startPoint.getLongitude();

        // 特殊情况处理
        if (Math.abs(dx) < 1e-6) { // 垂直线
            return Double.POSITIVE_INFINITY; // 斜率无穷大
        } else if (Math.abs(dy) < 1e-6) { // 水平线
            return 0; // 斜率为零
        } else {
            return dy / dx; // 正常情况
        }
    }
}
