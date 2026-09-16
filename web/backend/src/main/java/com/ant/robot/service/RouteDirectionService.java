package com.ant.robot.service;

import com.ant.robot.model.domain.Station;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 路线明细 direction 字段的业务规则。
 *
 * <p>规则与原 {@code RouteController#processDirectionField} <b>逐字一致</b>：</p>
 * <ul>
 *   <li>{@code 360}：自动取「该点到下一点」的航向角（末点退化为 0）</li>
 *   <li>{@code -360}：自动取「上一点到该点」的航向角（首点保留原值 -360）</li>
 *   <li>{@code -180..180}：原值保留（注意会格式化为 double 形式，如 "45"→"45.0"）</li>
 *   <li>其他：一律写 0</li>
 *   <li>任何解析异常：写 0</li>
 * </ul>
 *
 * <p><b>已知技术债</b>：同样的逻辑在 {@code RouteController} 中已重复存在 4 处
 * （约 877 / 943 / 1129 / 2849 行）。本次未改动 RouteController（2930 行、现场在用，
 * 重构风险高），因此这里是第 5 份实现。后续应统一抽取到本服务，再让 RouteController 调用。</p>
 *
 * @author ChenWeihan
 */
@Service
@Slf4j
public class RouteDirectionService {

    @Resource
    private StationService stationService;

    /**
     * 按业务规则处理某个站点在路线中的 direction 值。
     *
     * @param rawDirection 原始方向值（字符串形式）
     * @param stationIds   该路线的站点 ID 有序列表（对应明细顺序）
     * @param stationId    当前站点 ID
     * @return 处理后的方向值字符串
     */
    public String process(String rawDirection, List<Long> stationIds, Long stationId) {
        try {
            if (rawDirection == null) {
                return "0";
            }
            double directionValue = Double.parseDouble(rawDirection);

            int currentIndex = stationIds == null ? -1 : stationIds.indexOf(stationId);
            if (currentIndex == -1) {
                // 找不到索引时保持原值（与原实现一致）
                return rawDirection;
            }

            List<double[]> xyCoordinates = new ArrayList<>();
            for (Long id : stationIds) {
                Station station = stationService.getById(id);
                if (station != null) {
                    double x = Double.parseDouble(station.getPositionX());
                    double y = Double.parseDouble(station.getPositionY());
                    xyCoordinates.add(new double[]{x, y});
                } else {
                    xyCoordinates.add(new double[]{0, 0});
                }
            }

            if (directionValue == 360) {
                // 到下一个点的航向
                if (currentIndex < stationIds.size() - 1) {
                    double yaw = calculateYaw(xyCoordinates.get(currentIndex),
                            xyCoordinates.get(currentIndex + 1));
                    return String.valueOf(normalizeAngleTo180(yaw));
                }
                return "0";
            } else if (directionValue == -360) {
                // 与上一个点的航向
                if (currentIndex > 0) {
                    double yaw = calculateYaw(xyCoordinates.get(currentIndex - 1),
                            xyCoordinates.get(currentIndex));
                    return String.valueOf(normalizeAngleTo180(yaw));
                }
                // 首点保留原值（原实现即如此，虽为 -360 这种非角度值，但不改动以保持一致）
                return String.valueOf(directionValue);
            } else if (directionValue <= 180 && directionValue >= -180) {
                return String.valueOf(directionValue);
            } else {
                return "0";
            }
        } catch (Exception e) {
            log.error("处理方向字段失败，使用默认值 0", e);
            return "0";
        }
    }

    /**
     * 两点之间的航向角，规范化到 0-360 度。与原 {@code RouteController#calculateYaw} 一致。
     */
    private double calculateYaw(double[] from, double[] to) {
        double dx = to[0] - from[0];
        double dy = to[1] - from[1];
        double yawRad = Math.atan2(dy, dx);
        double yawDeg = Math.toDegrees(yawRad);
        return (yawDeg + 360) % 360;
    }

    /**
     * 角度规范到 -180~180。与原 {@code RouteController#normalizeAngleTo180} 一致。
     */
    static double normalizeAngleTo180(double angle) {
        angle = angle % 360;
        if (angle > 180) {
            angle -= 360;
        } else if (angle < -180) {
            angle += 360;
        }
        return angle;
    }
}
