package com.ant.robot.controller;

import com.alibaba.fastjson2.JSON;
import com.ant.robot.common.aop.LogAnnotation;
import com.ant.robot.common.enums.ErrorCode;
import com.ant.robot.common.response.BaseResponse;
import com.ant.robot.common.response.ResultUtils;
import com.ant.robot.mapper.ParamMapper;
import com.ant.robot.mapper.RouteDetailMapper;
import com.ant.robot.mapper.RouteMapper;
import com.ant.robot.mapper.StationMapper;
import com.ant.robot.model.domain.Param;
import com.ant.robot.model.domain.Route;
import com.ant.robot.model.domain.RouteDetail;
import com.ant.robot.model.domain.Station;
import com.ant.robot.model.request.*;
import com.ant.robot.service.*;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.baomidou.mybatisplus.extension.toolkit.Db.*;

/**
 * @author ChenWeihan
 * @description 修改路线名、删除路线、修改路线信息、保存路线
 */

@RestController
@RequestMapping("route")
@Slf4j
public class
RouteController {

    private static final int PATH_POINT_FIELD_COUNT = 9;
    private static final int PATH_POINT_HEADER_SIZE = 1;

    private int latestStationNumber;
    private int latestAutoNumber;
    private int latestEdgeNumber;
    private String prefix = "S";  // 固定字符部分
    private String Auto = "Auto"; // 固定字符部分
    private String Edge = "Edge"; // 固定字符部分

    private static final Random random = new Random();
    @Resource
    private RouteService routeService;
    @Resource
    private RouteMapper routeMapper;
    @Resource
    private ParamMapper paramMapper;
    @Resource
    private StationService stationService;
    @Resource
    private StationMapper stationMapper;
    @Resource
    private RouteDetailMapper routeDetailMapper;
    @Resource
    private RouteDetailService routeDetailService;
    @Autowired
    private StationController stationController;
    @Resource
    private LocationService locationService;
    @Resource
    private Llh2xyzController llh2xyzController;


    /**
     * 添加路线
     */
    @LogAnnotation(title = "路线模块", content = "新增路线")
    @Transactional(rollbackFor = Exception.class)
    @PostMapping("add")
    public BaseResponse<Boolean> addRoute(@RequestBody RouteRequest routeRequest) {
        // 1. 保存路线基本信息
        Route route = new Route();
        route.setRouteName(routeRequest.getRouteName());
        route.setStationIds(routeRequest.getStationIds());
        route.setMapCoverage(routeRequest.getMapCoverage());
        route.setRoutesource(routeRequest.getRoutesource());
        route.setSpeed(routeRequest.getSpeed());

        boolean saveRoute = routeService.save(route);
        Long routeId = route.getId();

        // 2. 处理路线详情数据
        List<RouteDetail> routeDetailList = new ArrayList<>();

        if (routeRequest.getRouteDetails() != null) {
            for (RouteDetailRequest detailRequest : routeRequest.getRouteDetails()) {
                RouteDetail routeDetail = new RouteDetail();
                routeDetail.setRouteId(routeId);
                routeDetail.setStationId(detailRequest.getStationId());
                routeDetail.setSpeed(detailRequest.getSpeed());
                routeDetail.setDirection(detailRequest.getDirection());
                routeDetail.setArea(detailRequest.getArea());
                routeDetail.setAction(detailRequest.getAction());
                routeDetail.setStopTime(detailRequest.getStopTime());
                routeDetailList.add(routeDetail);
            }
        }

        boolean saveRouteDetail = routeDetailService.saveBatch(routeDetailList);
        return ResultUtils.success(saveRoute && saveRouteDetail);
    }

    @LogAnnotation(title = "路线模块", content = "通过点序列新增路线")
    @Transactional(rollbackFor = Exception.class)
    @PostMapping("addWithPoints")
    public BaseResponse<Boolean> addRouteWithPoints(@RequestBody Map<String, Object> request) {
        log.info("addWithPoints request {}",request);
        try {

            QueryWrapper<Param> paramQueryWrapper = new QueryWrapper<>();
            Param param = paramMapper.selectOne(paramQueryWrapper);
            Double Local_Lat = Double.parseDouble(param.getLocal_origin_latitude());
            Double Local_Lon = Double.parseDouble(param.getLocal_origin_longitude());
            // 1. 解析请求数据
            String routeName = (String) request.get("routeName");
            String mapName = (String) request.get("mapName");
            String Speed = (String) request.get("speed");

            List<Map<String, Object>> points = (List<Map<String, Object>>) request.get("points");

            // 2. 处理站点数据
            List<Long> stationIds = new ArrayList<>();
            List<String> rowData = new ArrayList<>();

            for (Map<String, Object> point : points) {
                Map<String, Object> position = (Map<String, Object>) point.get("position");
                Map<String, Object> orientation = (Map<String, Object>) point.get("orientation");

                // 创建站点请求对象
                StationRequest stationRequest = new StationRequest();
                stationRequest.setStationName(generateStationName(prefix));
                stationRequest.setStationCode("0");

                // 设置位置信息
                stationRequest.setPositionX(String.valueOf(position.get("x")));
                stationRequest.setPositionY(String.valueOf(position.get("y")));
                stationRequest.setPositionZ(String.valueOf(position.get("z")));

                double[] latLon = llh2xyzController.convertFromLocalCoordinates(Local_Lat, Local_Lon,
                        Double.valueOf(String.valueOf(position.get("x"))),Double.valueOf(String.valueOf(position.get("y"))),
                        Double.valueOf(String.valueOf(position.get("z"))));

                stationRequest.setLongitude(String.valueOf(latLon[1]));
                stationRequest.setLatitude(String.valueOf(latLon[0]));

                // 设置方向信息
                stationRequest.setOrientationX(String.valueOf(orientation.get("x")));
                stationRequest.setOrientationY(String.valueOf(orientation.get("y")));
                stationRequest.setOrientationZ(String.valueOf(orientation.get("z")));
                stationRequest.setOrientationW(String.valueOf(orientation.get("w")));

                // 直接保存站点并获取ID
                Station station = new Station();
                BeanUtils.copyProperties(stationRequest, station);
                boolean saveResult = stationService.save(station);

                if (!saveResult) {
                    throw new RuntimeException("保存站点失败");
                }

                stationIds.add(station.getId());
                rowData.add("0");
            }

            // 3. 创建并保存路线
            Route route = new Route();
            route.setRouteName(routeName);
            route.setSpeed(Speed);
            route.setMapCoverage("20");
            route.setRoutesource("0");
            route.setMapName(mapName);

            QueryWrapper<Route> queryWrapper = new QueryWrapper<>();
            queryWrapper.select("MAX(CAST(routeGroup AS SIGNED)) as maxRouteGroup");
            Map<String, Object> map = routeService.getMap(queryWrapper);
            long latestRouteGroup = 0L; // 使用long类型接收
            if (map != null && map.get("maxRouteGroup") != null) {
                // 处理可能的Long或Integer返回值
                Object value = map.get("maxRouteGroup");
                if (value instanceof Long) {
                    latestRouteGroup = (Long) value;
                } else if (value instanceof Integer) {
                    latestRouteGroup = ((Integer) value).longValue();
                }
            }
            route.setRouteGroup(String.valueOf(latestRouteGroup + 1));

            // 将站点ID列表转为JSON字符串
            ObjectMapper objectMapper = new ObjectMapper();
            route.setStationIds(objectMapper.writeValueAsString(stationIds));

            boolean saveRoute = routeService.save(route);
            Long routeId = route.getId();

            // 4. 创建并保存路线详情
            List<RouteDetail> routeDetailList = new ArrayList<>();
            for (Long stationId : stationIds) {
                RouteDetail detail = new RouteDetail();
                detail.setRouteId(routeId);
                detail.setStationId(stationId);
                detail.setSpeed("0");
                detail.setDirection("0");
                detail.setArea("0");
                detail.setAction("0");
                detail.setStopTime("0");
                routeDetailList.add(detail);
            }

            boolean saveRouteDetail = routeDetailService.saveBatch(routeDetailList);

            return ResultUtils.success(saveRoute && saveRouteDetail);
        } catch (Exception e) {
            log.error("通过点序列新增路线失败", e);
            return ResultUtils.error(ErrorCode.OPERATION_ERROR, "通过点序列新增路线失败");
        }
    }

    /**
     * 删除路线
     */
    @LogAnnotation(title = "路线模块", content = "删除路线")
    @DeleteMapping("delete/{id}")
    public BaseResponse<Boolean> deleteRoute(@PathVariable("id") Long id) {
        boolean delete = routeService.removeById(id);
        return ResultUtils.success(delete);
    }

    /**
     * 修改路线
     */
    @LogAnnotation(title = "路线模块", content = "更新路线")
    @PostMapping("update")
    @Transactional(rollbackFor = Exception.class)
    public BaseResponse<Boolean> updateRoute(@RequestBody RouteUpdateRequest request) {
        try {
            // 1. 参数校验
            if (request.getRouteId() == null) {
                return ResultUtils.error(ErrorCode.PARAMS_ERROR, "路线ID不能为空");
            }

            // 2. 更新路线基本信息
            Route route = routeService.getById(request.getRouteId());
            if (route == null) {
                return ResultUtils.error(ErrorCode.NULL_ERROR, "路线不存在");
            }

            // 更新可修改的字段
            if (StringUtils.isNotBlank(request.getRouteName())) {
                route.setRouteName(request.getRouteName());
            }
            if (StringUtils.isNotBlank(request.getMapName())) {
                route.setMapName(request.getMapName());
            }
            if (request.getSpeed() != null) {
                route.setSpeed(String.valueOf(request.getSpeed()));
            }

            boolean updateRoute = routeService.updateById(route);

            // 3. 处理站点数据
            List<Long> updatedStationIds = new ArrayList<>();
            List<RouteDetail> routeDetails = new ArrayList<>();

            if (request.getPoints() != null && !request.getPoints().isEmpty()) {
                // 批量处理站点
                for (PointData point : request.getPoints()) {
                    StationData stationData = point.getStationData();

                    // 处理站点信息
                    Station station;
                    if (stationData.getId() == null || stationData.getId() == -1) {
                        // 新建站点
                        station = new Station();
                        station.setStationName(generateStationName(prefix));
                        station.setStationCode("0");
                    } else {
                        // 更新现有站点
                        station = stationService.getById(stationData.getId());
                        if (station == null) {
                            continue; // 跳过不存在的站点
                        }
                    }

                    // 更新/设置站点属性
                    station.setPositionX(stationData.getPositionX());
                    station.setPositionY(stationData.getPositionY());
                    station.setLongitude(String.valueOf(point.getX()));
                    station.setLatitude(String.valueOf(point.getY()));

                    // 保存站点
                    if (station.getId() == null) {
                        stationService.save(station);
                    } else {
                        stationService.updateById(station);
                    }

                    updatedStationIds.add(station.getId());

                    // 准备路线详情数据
                    RouteDetail detail = new RouteDetail();
                    detail.setRouteId(request.getRouteId());
                    detail.setStationId(station.getId());
                    detail.setSpeed(stationData.getSpeed());
                    detail.setDirection(stationData.getDirection());
                    detail.setArea(stationData.getArea());
                    detail.setAction(stationData.getAction());
                    detail.setStopTime(stationData.getStopTime());
                    routeDetails.add(detail);
                }

                // 4. 更新路线关联的站点ID列表
                route.setStationIds(JSON.toJSONString(updatedStationIds));
                routeService.updateById(route);

                // 5. 更新路线详情数据
                // 先删除旧的详情数据
                routeDetailService.remove(new QueryWrapper<RouteDetail>()
                        .eq("routeId", request.getRouteId()));

                // 再插入新的详情数据
                if (!routeDetails.isEmpty()) {
                    routeDetailService.saveBatch(routeDetails);
                }
            }

            return ResultUtils.success(updateRoute);
        } catch (Exception e) {
            log.error("更新路线失败", e);
            return ResultUtils.error(ErrorCode.OPERATION_ERROR, "更新路线失败");
        }
    }

    /**
     * 更新路线中某个站点的RouteDetail信息
     */
    @LogAnnotation(title = "路线模块", content = "更新路线站点详情")
    @PostMapping("updateRouteDetail")
    @Transactional(rollbackFor = Exception.class)
    public BaseResponse<Boolean> updateRouteDetail(@RequestBody RouteDetailRequest request) {
        try {
            // 1. 参数校验
            if (request.getRouteId() == null || request.getStationId() == null) {
                return ResultUtils.error(ErrorCode.PARAMS_ERROR, "路线ID和站点ID不能为空");
            }

            // 2. 检查路线是否存在
            Route route = routeService.getById(request.getRouteId());
            if (route == null) {
                return ResultUtils.error(ErrorCode.NULL_ERROR, "路线不存在");
            }

            // 3. 检查站点是否存在
            Station station = stationService.getById(request.getStationId());
            if (station == null) {
                return ResultUtils.error(ErrorCode.NULL_ERROR, "站点不存在");
            }

            // 4. 检查站点是否属于该路线
            List<Long> stationIds = JSON.parseArray(route.getStationIds(), Long.class);
            if (!stationIds.contains(request.getStationId())) {
                return ResultUtils.error(ErrorCode.PARAMS_ERROR, "该站点不属于当前路线");
            }

            // 5. 处理Direction字段的特殊逻辑（按照saveRoute中的方式）
            String processedDirection = processDirectionField(request, route, stationIds);

            // 6. 查询现有的RouteDetail记录
            QueryWrapper<RouteDetail> queryWrapper = new QueryWrapper<>();
            queryWrapper.eq("routeId", request.getRouteId())
                    .eq("stationId", request.getStationId());

            RouteDetail existingDetail = routeDetailService.getOne(queryWrapper);

            // 7. 如果不存在则创建新记录，存在则更新
            RouteDetail detailToSave = existingDetail != null ? existingDetail : new RouteDetail();

            detailToSave.setRouteId(request.getRouteId());
            detailToSave.setStationId(request.getStationId());

            // 只更新非空字段
            if (request.getSpeed() != null) {
                detailToSave.setSpeed(request.getSpeed());
            }
            if (request.getArea() != null) {
                detailToSave.setArea(request.getArea());
            }
            if (request.getDirection() != null) {
                // 使用处理后的方向值
                detailToSave.setDirection(processedDirection);
            }
            if (request.getStopTime() != null) {
                detailToSave.setStopTime(request.getStopTime());
            }
            if (request.getAction() != null) {
                detailToSave.setAction(request.getAction());
            }
            if (request.getPosition() != null) {
                detailToSave.setPosition(request.getPosition());
            }
            if (request.getStop() != null) {
                detailToSave.setStop(request.getStop());
            }
            if (request.getRunmode() != null) {
                detailToSave.setRunmode(request.getRunmode());
            }
            if (request.getLanechange() != null) {
                detailToSave.setLanechange(request.getLanechange());
            }

            // 8. 保存或更新
            boolean result = existingDetail != null
                    ? routeDetailService.updateById(detailToSave)
                    : routeDetailService.save(detailToSave);

            return ResultUtils.success(result);
        } catch (Exception e) {
            log.error("更新路线站点详情失败", e);
            return ResultUtils.error(ErrorCode.OPERATION_ERROR, "更新路线站点详情失败");
        }
    }

    /**
     * 批量更新路线下所有站点的RouteDetail信息
     */
    @LogAnnotation(title = "路线模块", content = "批量更新路线站点详情")
    @PostMapping("updateAllRouteDetails/{routeId}")
    @Transactional(rollbackFor = Exception.class)
    public BaseResponse<Boolean> updateAllRouteDetails(@PathVariable("routeId") Long routeId, @RequestBody RouteDetailRequest request) {
        try {
            // 1. 参数校验
            if (routeId == null) {
                return ResultUtils.error(ErrorCode.PARAMS_ERROR, "路线ID不能为空");
            }

            // 2. 检查路线是否存在
            Route route = routeService.getById(routeId);
            if (route == null) {
                return ResultUtils.error(ErrorCode.NULL_ERROR, "路线不存在");
            }

            // 3. 获取该路线下的所有站点ID
            List<Long> stationIds = parseStationIds(route.getStationIds());
            if (CollectionUtils.isEmpty(stationIds)) {
                return ResultUtils.error(ErrorCode.NULL_ERROR, "该路线下没有站点");
            }

            // 4. 查询现有的所有RouteDetail记录
            QueryWrapper<RouteDetail> queryWrapper = new QueryWrapper<>();
            queryWrapper.eq("routeId", routeId)
                    .in("stationId", stationIds);

            List<RouteDetail> existingDetails = routeDetailService.list(queryWrapper);
            Map<Long, RouteDetail> stationIdToDetailMap = existingDetails.stream()
                    .collect(Collectors.toMap(RouteDetail::getStationId, Function.identity()));

            // 5. 批量更新或创建RouteDetail记录
            List<RouteDetail> detailsToSaveOrUpdate = new ArrayList<>();
            List<Long> missingStationIds = new ArrayList<>();

            for (Long stationId : stationIds) {
                // 检查站点是否存在
                Station station = stationService.getById(stationId);
                if (station == null) {
                    log.warn("站点不存在: {}", stationId);
                    continue;
                }

                RouteDetail existingDetail = stationIdToDetailMap.get(stationId);
                RouteDetail detailToSave = existingDetail != null ? existingDetail : new RouteDetail();

                // 设置基本属性
                detailToSave.setRouteId(routeId);
                detailToSave.setStationId(stationId);

                // 使用传入的请求参数覆盖现有值（只更新非空字段）
                if (request.getSpeed() != null) {
                    detailToSave.setSpeed(request.getSpeed());
                }
                if (request.getArea() != null) {
                    detailToSave.setArea(request.getArea());
                }
                if (request.getDirection() != null) {
                    detailToSave.setDirection(request.getDirection());
                }
                if (request.getStopTime() != null) {
                    detailToSave.setStopTime(request.getStopTime());
                }
                if (request.getAction() != null) {
                    detailToSave.setAction(request.getAction());
                }
                if (request.getPosition() != null) {
                    detailToSave.setPosition(request.getPosition());
                }
                if (request.getStop() != null) {
                    detailToSave.setStop(request.getStop());
                }
                if (request.getRunmode() != null) {
                    detailToSave.setRunmode(request.getRunmode());
                }

                if (request.getLanechange() != null) {
                    detailToSave.setLanechange(request.getLanechange());
                }

                detailsToSaveOrUpdate.add(detailToSave);

                // 记录需要新建的记录
                if (existingDetail == null) {
                    missingStationIds.add(stationId);
                }
            }

            // 6. 批量保存或更新
            boolean result = true;

            if (!detailsToSaveOrUpdate.isEmpty()) {
                // 分批处理，避免SQL语句过长
                int batchSize = 100;
                for (int i = 0; i < detailsToSaveOrUpdate.size(); i += batchSize) {
                    int end = Math.min(i + batchSize, detailsToSaveOrUpdate.size());
                    List<RouteDetail> batch = detailsToSaveOrUpdate.subList(i, end);

                    // 区分更新和插入
                    List<RouteDetail> toUpdate = new ArrayList<>();
                    List<RouteDetail> toInsert = new ArrayList<>();

                    for (RouteDetail detail : batch) {
                        if (detail.getId() != null) {
                            toUpdate.add(detail);
                        } else {
                            toInsert.add(detail);
                        }
                    }

                    if (!toUpdate.isEmpty()) {
                        result = result && routeDetailService.updateBatchById(toUpdate);
                    }
                    if (!toInsert.isEmpty()) {
                        result = result && routeDetailService.saveBatch(toInsert);
                    }
                }
            }

            // 7. 记录操作日志
            if (!missingStationIds.isEmpty()) {
                log.info("为路线 {} 新创建了 {} 个站点的RouteDetail记录", routeId, missingStationIds.size());
            }

            return ResultUtils.success(result);
        } catch (Exception e) {
            log.error("批量更新路线站点详情失败", e);
            return ResultUtils.error(ErrorCode.OPERATION_ERROR, "批量更新路线站点详情失败");
        }
    }

    /**
     * 查询所有路线
     */
    @GetMapping("queryall")
    public BaseResponse<List<Map<String, Object>>> queryAll() {
        try {
            // 1. 查询所有路线列表
            List<Route> routeList = routeService.list();

            if (CollectionUtils.isEmpty(routeList)) {
                return ResultUtils.success(Collections.emptyList());
            }

            // 2. 收集所有站点ID和路线ID
            Set<Long> allStationIds = new HashSet<>();
            List<Long> routeIds = new ArrayList<>();

            for (Route route : routeList) {
                routeIds.add(route.getId());
                // 支持多种格式的stationIds解析
                List<Long> stationIds = parseStationIds(route.getStationIds());
                allStationIds.addAll(stationIds);
            }

            // 3. 批量查询所有站点和路线详情
            Map<Long, Station> stationMap = allStationIds.isEmpty() ?
                    Collections.emptyMap() :
                    stationService.listByIds(allStationIds).stream()
                            .collect(Collectors.toMap(Station::getId, Function.identity()));

            Map<Long, List<RouteDetail>> routeDetailsMap = routeDetailService.list(
                    new QueryWrapper<RouteDetail>().in("routeId", routeIds)
            ).stream().collect(Collectors.groupingBy(RouteDetail::getRouteId));

            // 4. 构建返回结果
            List<Map<String, Object>> result = new ArrayList<>();

            // 按routeId排序，确保自动规划路线和边界路线相邻
            List<Route> sortedRoutes = new ArrayList<>(routeList);
            sortedRoutes.sort(Comparator.comparing(Route::getId));

            // 使用标记来跳过已处理的边界路线
            boolean[] processed = new boolean[sortedRoutes.size()];

            for (int i = 0; i < sortedRoutes.size(); i++) {
                if (processed[i]) {
                    continue; // 跳过已处理的边界路线
                }

                Route route = sortedRoutes.get(i);
                processed[i] = true;

                Map<String, Object> routeData = new HashMap<>();
                routeData.put("routeId", route.getId());
                routeData.put("routeName", route.getRouteName());
                routeData.put("mapCoverage", route.getMapCoverage());
                routeData.put("routesource", route.getRoutesource());
                routeData.put("speed", route.getSpeed());

                // 处理站点信息
                List<Long> stationIds = parseStationIds(route.getStationIds());
                List<Map<String, Object>> stations = new ArrayList<>();

                for (Long stationId : stationIds) {
                    Station station = stationMap.get(stationId);
                    if (station != null) {
                        Map<String, Object> stationData = new HashMap<>();
                        stationData.put("stationId", station.getId());
                        stationData.put("stationName", station.getStationName());
                        stationData.put("getPositionX", station.getPositionX());
                        stationData.put("getPositionY", station.getPositionY());
                        stationData.put("longitude", station.getLongitude());
                        stationData.put("latitude", station.getLatitude());

                        // 添加路线详情信息
                        RouteDetail detail = routeDetailsMap.getOrDefault(route.getId(), Collections.emptyList())
                                .stream()
                                .filter(rd -> rd.getStationId().equals(stationId))
                                .findFirst()
                                .orElse(null);

                        stationData.put("stop", detail != null && detail.getStop() != null ? detail.getStop() : "0");
                        stationData.put("area", detail != null && detail.getArea() != null ? detail.getArea() : "0");
                        stationData.put("speed", detail != null && detail.getSpeed() != null ? detail.getSpeed() : "0");
                        stationData.put("action", detail != null && detail.getAction() != null ? detail.getAction() : "0");
                        stationData.put("direction", detail != null && detail.getDirection() != null ? detail.getDirection() : "0");
                        stationData.put("stoptime", detail != null && detail.getStopTime() != null ? detail.getStopTime() : "0");
                        stationData.put("position", detail != null && detail.getPosition() != null ? detail.getPosition() : "0");
                        stationData.put("runmode", detail != null && detail.getRunmode() != null ? detail.getRunmode() : "0");
                        stationData.put("lanechange", detail != null && detail.getLanechange() != null ? detail.getLanechange() : "0");

                        stations.add(stationData);
                    }
                }

                routeData.put("stations", stations);
                // 处理edges逻辑 - 当routesource为"1"时获取下一条路线作为edges
                if ("1".equals(route.getRoutesource())) {
                    // 检查是否有下一条路线且未被处理
                    if (i + 1 < sortedRoutes.size() && !processed[i + 1]) {
                        Route nextRoute = sortedRoutes.get(i + 1);

                        // 验证下一条路线是否为边界路线（通常名称包含"Edge"）
                        if (nextRoute.getRouteName() != null &&
                                (nextRoute.getRouteName().contains("Edge") ||
                                        nextRoute.getRouteName().startsWith("Edge"))) {

                            List<Long> edgeStationIds = parseStationIds(nextRoute.getStationIds());
                            List<Map<String, Object>> edges = new ArrayList<>();

                            for (Long stationId : edgeStationIds) {
                                Station station = stationMap.get(stationId);
                                if (station != null) {
                                    Map<String, Object> edgeData = new HashMap<>();
                                    edgeData.put("stationId", station.getId());
                                    edgeData.put("stationName", station.getStationName());
                                    edgeData.put("getPositionX", station.getPositionX());
                                    edgeData.put("getPositionY", station.getPositionY());
                                    edgeData.put("longitude", station.getLongitude());
                                    edgeData.put("latitude", station.getLatitude());

                                    // 添加边界路线的详情信息（如果有）
                                    RouteDetail edgeDetail = routeDetailsMap.getOrDefault(nextRoute.getId(), Collections.emptyList())
                                            .stream()
                                            .filter(rd -> rd.getStationId().equals(stationId))
                                            .findFirst()
                                            .orElse(null);

                                    edgeData.put("area", edgeDetail != null && edgeDetail.getArea() != null ? edgeDetail.getArea() : "0");
                                    edges.add(edgeData);
                                }
                            }
                            routeData.put("edges", edges);

                            // 标记下一条路线为已处理
                            processed[i + 1] = true;
                        } else {
                            // 下一条路线不是边界路线
                            routeData.put("edges", Collections.emptyList());
                        }
                    } else {
                        // 没有下一条路线或下一条路线已被处理
                        routeData.put("edges", Collections.emptyList());
                    }
                } else {
                    // 非自动规划路线，edges为空
                    routeData.put("edges", Collections.emptyList());
                }

                result.add(routeData);
            }

            return ResultUtils.success(result);
        } catch (Exception e) {
            log.error("查询所有路线失败", e);
            return ResultUtils.error(ErrorCode.OPERATION_ERROR, "查询所有路线失败");
        }
    }


    /**
     * 根据路线id查询路线
     */
    @DeleteMapping("query/{id}")
    public BaseResponse<Route> queryRoute(@PathVariable("id") Long id) {
        QueryWrapper<Route> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("id", id);
        Route route = routeMapper.selectOne(queryWrapper);
        if (route == null) {
            return ResultUtils.error(ErrorCode.NULL_ERROR, "路线不存在");
        }
        return ResultUtils.success(route);
    }

    /**
     * 根据路线id查询路线数据发送给车辆
     */
    @GetMapping("detail/{id}")
    @Transactional(rollbackFor = Exception.class)
    public BaseResponse<List<Object>> queryRouteDetailData(@PathVariable("id") Long id) {
        return buildPathPointData(id);
    }

    /**********************************************路线保存全适应****************************************************/
    @LogAnnotation(title = "路线模块", content = "保存路线")
    @Transactional(rollbackFor = Exception.class)
    @PostMapping("saveRoute")
    public BaseResponse<Boolean> saveRoute(@RequestBody RouteAllRequest request) {
        try {
            // 输入验证
            if (request == null || request.getStations() == null || request.getStations().isEmpty()) {
                return ResultUtils.error(ErrorCode.PARAMS_ERROR, "请求参数不能为空");
            }

            // 1. 保存或更新路线基本信息
            Route route = new Route();
            if (request.getId() != null) {
                Route existingRoute = routeService.getById(request.getId());
                if (existingRoute != null) {
                    BeanUtils.copyProperties(existingRoute, route);
                }
            }
            route.setRouteName(request.getRouteName());
            route.setMapCoverage(request.getMapCoverage());
            route.setRoutesource(request.getRoutesource());
            route.setMapName(request.getMapName());

            // 修改：获取当前最新的RouteGroup并加1
            QueryWrapper<Route> queryWrapper = new QueryWrapper<>();
            queryWrapper.select("MAX(CAST(routeGroup AS SIGNED)) as maxRouteGroup");
            Map<String, Object> map = routeService.getMap(queryWrapper);
            long latestRouteGroup = 0L; // 使用long类型接收
            if (map != null && map.get("maxRouteGroup") != null) {
                // 处理可能的Long或Integer返回值
                Object value = map.get("maxRouteGroup");
                if (value instanceof Long) {
                    latestRouteGroup = (Long) value;
                } else if (value instanceof Integer) {
                    latestRouteGroup = ((Integer) value).longValue();
                }
            }
            route.setRouteGroup(String.valueOf(latestRouteGroup + 1));

            route.setSpeed(request.getSpeed());

            // 获取当前数据库中路线数量用于生成站点名称
            int routeCount = Math.toIntExact(routeService.count() + 1);

            // 2. 预处理所有站点的XY坐标
            List<double[]> xyCoordinates = new ArrayList<>();

            // 获取本地坐标系原点经纬度
            QueryWrapper<Param> paramQueryWrapper = new QueryWrapper<>();
            Param param = paramMapper.selectOne(paramQueryWrapper);
            double localLat = param != null && StringUtils.isNotBlank(param.getLocal_origin_latitude())
                    ? Double.parseDouble(param.getLocal_origin_latitude()) : Double.NaN;
            double localLon = param != null && StringUtils.isNotBlank(param.getLocal_origin_longitude())
                    ? Double.parseDouble(param.getLocal_origin_longitude()) : Double.NaN;

            // 先处理所有站点的位置信息并计算XY坐标
            for (int i = 0; i < request.getStations().size(); i++) {
                RouteAllRequest.RouteStation stationRequest = request.getStations().get(i);
                try {
                    // 处理站点位置信息（验证并补充缺失的坐标）
                    processStationPosition(stationRequest, localLat, localLon, request.getStations(), i);

                    // 计算XY坐标
                    double[] xy = {Double.parseDouble(stationRequest.getPositionX()),
                            Double.parseDouble(stationRequest.getPositionY())};
                    xyCoordinates.add(xy);
                } catch (Exception e) {
                    log.error("预处理站点 {} 坐标失败: {}", stationRequest.getStationName(), e.getMessage());
                    throw new RuntimeException("预处理站点 " + stationRequest.getStationName() + " 坐标失败", e);
                }
            }

            // 3. 处理站点数据 - 只保存新站点，不更新现有站点
            List<Long> stationIds = new ArrayList<>();
            List<RouteDetail> routeDetails = new ArrayList<>();

            for (int i = 0; i < request.getStations().size(); i++) {
                RouteAllRequest.RouteStation stationRequest = request.getStations().get(i);
                try {
                    // 只处理新站点（ID为null或<=0的站点）
                    if (stationRequest.getId() == null || stationRequest.getId() <= 0) {
                        // 创建新站点
                        Station station = new Station();
                        BeanUtils.copyProperties(stationRequest, station);

                        // 确保不设置ID，让数据库自动生成
                        station.setId(null);

                        // 生成站点名称 - 特殊处理ID为-1的情况
                        if (stationRequest.getId() != null && stationRequest.getId() == -1) {
                            // 格式: Lm-Pn，m为当前数据库中路线数量+1，n为该点为第几个点
                            station.setStationName(String.format("L%d-P%d", routeCount, i + 1));
                        } else if (StringUtils.isBlank(station.getStationName())) {
                            station.setStationName(generateStationName(prefix));
                        }

                        // 保存新站点
                        boolean saveResult = stationService.save(station);
                        if (!saveResult) {
                            throw new RuntimeException("保存站点失败");
                        }

                        // 添加到站点ID列表
                        stationIds.add(station.getId());

                        // 准备路线详情数据
                        RouteDetail detail = new RouteDetail();
                        detail.setStationId(station.getId());

                        // 处理Direction字段的特殊逻辑
                        if (stationRequest.getDirection() != null) {
                            double directionValue = Double.parseDouble(stationRequest.getDirection());

                            log.info("directionValue {}", directionValue);

                            if (directionValue == 360.0) {
                                // 情况1: Direction=360，计算该点到下一点的航向角
                                if (i < request.getStations().size() - 1) {
                                    double[] currentXY = xyCoordinates.get(i);
                                    double[] nextXY = xyCoordinates.get(i + 1);
                                    double yaw = calculateYaw(currentXY, nextXY);
                                    yaw = normalizeAngleTo180(yaw); // 添加角度规整
                                    detail.setDirection(String.valueOf(yaw));
                                } else {
                                    // 如果是最后一个点，保持原方向或设为默认值
                                    detail.setDirection("0");
                                }
                            } else if (directionValue == -360.0) {
                                // 情况2: Direction=-360，计算该点与上一点的航向角
                                log.info("directionValue {}", directionValue);

                                if (i > 0) {
                                    double[] prevXY = xyCoordinates.get(i - 1);
                                    double[] currentXY = xyCoordinates.get(i);
                                    log.info("前一点坐标: [{}, {}], 当前点坐标: [{}, {}]",
                                            prevXY[0], prevXY[1], currentXY[0], currentXY[1]);

                                    double baseYaw = calculateYaw(prevXY, currentXY);
                                    log.info("计算的基础航向角: {}", baseYaw);

                                    double adjustedYaw = baseYaw;
                                    adjustedYaw = normalizeAngleTo180(adjustedYaw); // 添加角度规整
                                    log.info("调整后的航向角: {}", adjustedYaw);

                                    detail.setDirection(String.valueOf(adjustedYaw));
                                } else {
                                    log.info("第一个点，直接使用传入值: {}", directionValue);
                                    detail.setDirection(String.valueOf(directionValue));
                                }
                            } else if (directionValue <= 180 && directionValue >= -180) {
                                // 情况3: Direction在-180到180之间，使用传入值
                                log.info("directionValue {}", directionValue);
                                detail.setDirection(String.valueOf(directionValue));
                            }
                        } else {
                            detail.setDirection("0");
                        }

                        // 为每个字段设置默认值（如果为null）
                        detail.setSpeed(stationRequest.getSpeed() != null ? stationRequest.getSpeed() : "0");
                        detail.setRunmode(stationRequest.getRunmode() != null ? stationRequest.getRunmode() : "0");
                        detail.setAction(stationRequest.getAction() != null ? stationRequest.getAction() : "0");
                        detail.setArea(stationRequest.getArea() != null ? stationRequest.getArea() : "0");
                        detail.setStopTime(stationRequest.getStopTime() != null ? stationRequest.getStopTime() : "0");
                        detail.setPosition(stationRequest.getPosition() != null ? stationRequest.getPosition() : "0");
                        detail.setStop(stationRequest.getStop() != null ? stationRequest.getStop() : "0");
                        detail.setLanechange(stationRequest.getLanechange() != null ? stationRequest.getLanechange() : "0");

                        log.info("Direction {}", detail.getDirection());

                        routeDetails.add(detail);
                    } else {
                        // 对于已有站点，直接使用其ID
                        stationIds.add(stationRequest.getId());

                        RouteDetail detail = new RouteDetail();
                        detail.setStationId(stationRequest.getId());

                        if (stationRequest.getDirection() != null) {
                            double directionValue = Double.parseDouble(stationRequest.getDirection());

                            if (directionValue == 360) {
                                // 情况1: Direction=360，计算该点到下一点的航向角
                                if (i < request.getStations().size() - 1) {
                                    double[] currentXY = xyCoordinates.get(i);
                                    double[] nextXY = xyCoordinates.get(i + 1);
                                    double yaw = calculateYaw(currentXY, nextXY);
                                    yaw = normalizeAngleTo180(yaw); // 添加角度规整
                                    detail.setDirection(String.valueOf(yaw));
                                } else {
                                    // 如果是最后一个点，保持原方向或设为默认值
                                    detail.setDirection("0");
                                }
                            } else if (directionValue == -360) {
                                // 情况2: Direction=-360，计算该点与上一点的航向角
                                log.info("directionValue {}", directionValue);

                                if (i > 0) {
                                    double[] prevXY = xyCoordinates.get(i - 1);
                                    double[] currentXY = xyCoordinates.get(i);
                                    log.info("前一点坐标: [{}, {}], 当前点坐标: [{}, {}]",
                                            prevXY[0], prevXY[1], currentXY[0], currentXY[1]);

                                    double baseYaw = calculateYaw(prevXY, currentXY);
                                    log.info("计算的基础航向角: {}", baseYaw);

                                    double adjustedYaw = baseYaw;
                                    adjustedYaw = normalizeAngleTo180(adjustedYaw); // 添加角度规整
                                    log.info("调整后的航向角: {}", adjustedYaw);

                                    detail.setDirection(String.valueOf(adjustedYaw));
                                } else {
                                    log.info("第一个点，直接使用传入值: {}", directionValue);
                                    detail.setDirection(String.valueOf(directionValue));
                                }
                            } else if (directionValue <= 180 && directionValue >= -180) {
                                // 情况3: Direction在-180到180之间，使用传入值
                                log.info("directionValue {}", directionValue);
                                detail.setDirection(String.valueOf(directionValue));
                            }
                        } else {
                            detail.setDirection("0");
                        }

                        // 为每个字段设置默认值（如果为null）
                        detail.setSpeed(stationRequest.getSpeed() != null ? stationRequest.getSpeed() : "0");
                        detail.setRunmode(stationRequest.getRunmode() != null ? stationRequest.getRunmode() : "0");
                        detail.setAction(stationRequest.getAction() != null ? stationRequest.getAction() : "0");
                        detail.setArea(stationRequest.getArea() != null ? stationRequest.getArea() : "0");
                        detail.setStopTime(stationRequest.getStopTime() != null ? stationRequest.getStopTime() : "0");
                        detail.setPosition(stationRequest.getPosition() != null ? stationRequest.getPosition() : "0");
                        detail.setStop(stationRequest.getStop() != null ? stationRequest.getStop() : "0");
                        detail.setLanechange(stationRequest.getLanechange() != null ? stationRequest.getLanechange() : "0");

                        routeDetails.add(detail);
                    }
                } catch (Exception e) {
                    log.error("处理站点 {} 失败: {}", stationRequest.getStationName(), e.getMessage());
                    throw new RuntimeException("处理站点 " + stationRequest.getStationName() + " 失败", e);
                }
            }

            ObjectMapper objectMapper = new ObjectMapper();

            // 将 stationIds 转换为 JSON 数组格式
            String stationIdsString = objectMapper.writeValueAsString(stationIds);

            route.setStationIds(stationIdsString);

            // 保存或更新路线
            boolean routeSaved = request.getId() == null ?
                    routeService.save(route) : routeService.updateById(route);

            if (!routeSaved) {
                throw new RuntimeException("保存路线失败");
            }

            // 获取路线ID
            Long routeId = route.getId();
            if (routeId == null) {
                throw new RuntimeException("获取路线ID失败");
            }

            // 先删除旧的路线详情
            if (request.getId() != null) {
                routeDetailService.remove(new QueryWrapper<RouteDetail>()
                        .eq("routeId", routeId));
            }

            // 保存新的路线详情
            for (RouteDetail detail : routeDetails) {
                detail.setRouteId(routeId);
            }
            boolean detailsSaved = routeDetailService.saveBatch(routeDetails);

            return ResultUtils.success(routeSaved && detailsSaved);
        } catch (Exception e) {
            log.error("保存路线失败", e);
            return ResultUtils.error(ErrorCode.OPERATION_ERROR, "保存路线失败: " + e.getMessage());
        }
    }

    @LogAnnotation(title = "路线模块", content = "更新路线")
    @Transactional(rollbackFor = Exception.class)
    @PostMapping("updateRoute")
    public BaseResponse<Boolean> updateRoute(@RequestBody RouteAllRequest request) {
        try {
            // 1. 参数校验 - 只检查必要参数
            if (request.getId() == null) {
                return ResultUtils.error(ErrorCode.PARAMS_ERROR, "路线ID不能为空");
            }

            // 2. 检查路线是否存在
            Route existingRoute = routeService.getById(request.getId());
            if (existingRoute == null) {
                return ResultUtils.error(ErrorCode.NULL_ERROR, "路线不存在");
            }

            // 3. 获取本地坐标系原点经纬度
            QueryWrapper<Param> paramQueryWrapper = new QueryWrapper<>();
            Param param = paramMapper.selectOne(paramQueryWrapper);
            double localLat = param != null && StringUtils.isNotBlank(param.getLocal_origin_latitude())
                    ? Double.parseDouble(param.getLocal_origin_latitude()) : Double.NaN;
            double localLon = param != null && StringUtils.isNotBlank(param.getLocal_origin_longitude())
                    ? Double.parseDouble(param.getLocal_origin_longitude()) : Double.NaN;

            // 4. 创建更新后的路线对象 - 只复制非空属性
            Route routeToUpdate = new Route();
            routeToUpdate.setId(request.getId());

            // 只更新传入的属性
            if (request.getRouteName() != null) {
                routeToUpdate.setRouteName(request.getRouteName());
            }
            if (request.getMapCoverage() != null) {
                routeToUpdate.setMapCoverage(request.getMapCoverage());
            }
            if (request.getRoutesource() != null) {
                routeToUpdate.setRoutesource(request.getRoutesource());
            }
            if (request.getSpeed() != null) {
                routeToUpdate.setSpeed(request.getSpeed());
            }
            if (request.getMapName() != null) {
                routeToUpdate.setMapName(request.getMapName());
            }
            if (request.getRouteGroup() != null) {
                routeToUpdate.setRouteGroup(request.getRouteGroup());
            }

            // 5. 处理站点数据（只有传入stations时才处理）
            List<Long> stationIds = null;
            List<RouteDetail> routeDetails = null;

            if (request.getStations() != null) {
                stationIds = new ArrayList<>();
                routeDetails = new ArrayList<>();

                // 预计算所有站点的XY坐标用于方向计算
                List<double[]> xyCoordinates = new ArrayList<>();
                for (int i = 0; i < request.getStations().size(); i++) {
                    RouteAllRequest.RouteStation stationRequest = request.getStations().get(i);
                    // 处理站点位置信息
                    processStationPosition(stationRequest, localLat, localLon, request.getStations(), i);

                    // 计算XY坐标
                    double[] xy = {Double.parseDouble(stationRequest.getPositionX()),
                            Double.parseDouble(stationRequest.getPositionY())};
                    xyCoordinates.add(xy);
                }

                for (int i = 0; i < request.getStations().size(); i++) {
                    RouteAllRequest.RouteStation stationRequest = request.getStations().get(i);

                    // 保存或更新站点
                    Long stationId = saveOrUpdateStation(stationRequest, request.getStations(), i);
                    stationIds.add(stationId);

                    // 准备路线详情数据
                    RouteDetail detail = new RouteDetail();
                    detail.setRouteId(request.getId());
                    detail.setStationId(stationId);

                    // 处理Direction字段的特殊逻辑（与saveRoute保持一致）
                    if (stationRequest.getDirection() != null) {
                        double directionValue = Double.parseDouble(stationRequest.getDirection());

                        log.info("处理站点 {} 的方向值: {}", stationRequest.getStationName(), directionValue);

                        if (directionValue == 360) {
                            // 情况1: Direction=360，计算该点到下一点的航向角
                            if (i < request.getStations().size() - 1) {
                                double[] currentXY = xyCoordinates.get(i);
                                double[] nextXY = xyCoordinates.get(i + 1);
                                double yaw = calculateYaw(currentXY, nextXY);
                                yaw = normalizeAngleTo180(yaw); // 添加角度规整
                                detail.setDirection(String.valueOf(yaw));
                                log.info("计算到下一点的航向角: {}", yaw);
                            } else {
                                // 如果是最后一个点，保持原方向或设为默认值
                                detail.setDirection("0");
                                log.info("最后一个点，使用默认方向: 0");
                            }
                        } else if (directionValue == -360) {
                            // 情况2: Direction=-360，计算该点与上一点的航向角
                            if (i > 0) {
                                double[] prevXY = xyCoordinates.get(i - 1);
                                double[] currentXY = xyCoordinates.get(i);
                                log.info("前一点坐标: [{}, {}], 当前点坐标: [{}, {}]",
                                        prevXY[0], prevXY[1], currentXY[0], currentXY[1]);

                                double baseYaw = calculateYaw(prevXY, currentXY);
                                log.info("计算的基础航向角: {}", baseYaw);

                                double adjustedYaw = baseYaw;
                                adjustedYaw = normalizeAngleTo180(adjustedYaw); // 添加角度规整
                                log.info("调整后的航向角: {}", adjustedYaw);

                                detail.setDirection(String.valueOf(adjustedYaw));
                            } else {
                                log.info("第一个点，直接使用传入值: {}", directionValue);
                                detail.setDirection(String.valueOf(directionValue));
                            }
                        } else if (directionValue <= 180 && directionValue >= -180) {
                            // 情况3: Direction在-180到180之间，使用传入值
                            log.info("使用传入方向值: {}", directionValue);
                            detail.setDirection(String.valueOf(directionValue));
                        } else {
                            // 其他情况使用默认值
                            detail.setDirection("0");
                            log.info("方向值超出范围，使用默认值: 0");
                        }
                    } else {
                        detail.setDirection("0");
                        log.info("方向值为空，使用默认值: 0");
                    }

                    // 为每个字段设置默认值（如果为null）
                    detail.setSpeed(getDefaultIfBlank(stationRequest.getSpeed(), "0"));
                    detail.setRunmode(stationRequest.getRunmode() != null ? stationRequest.getRunmode() : "0");
                    detail.setAction(getDefaultIfBlank(stationRequest.getAction(), "0"));
                    detail.setArea(getDefaultIfBlank(stationRequest.getArea(), "0"));
                    detail.setStopTime(getDefaultIfBlank(stationRequest.getStopTime(), "0"));
                    detail.setPosition(getDefaultIfBlank(stationRequest.getPosition(), "0"));
                    detail.setStop(getDefaultIfBlank(stationRequest.getStop(), "0"));
                    detail.setLanechange(stationRequest.getLanechange() != null ? stationRequest.getLanechange() : "0");

                    routeDetails.add(detail);
                }

                // 只有处理了站点才更新stationIds
                routeToUpdate.setStationIds(JSON.toJSONString(stationIds));
            }

            // 6. 更新路线基本信息
            boolean updateRouteResult = routeService.updateById(routeToUpdate);

            // 7. 只有传入了stations才更新路线详情
            if (request.getStations() != null) {
                // 先删除旧的详情数据
                QueryWrapper<RouteDetail> detailQueryWrapper = new QueryWrapper<>();
                detailQueryWrapper.eq("routeId", request.getId());
                routeDetailService.remove(detailQueryWrapper);

                // 再插入新的详情数据
                boolean saveDetailsResult = routeDetailService.saveBatch(routeDetails);
                return ResultUtils.success(updateRouteResult && saveDetailsResult);
            }

            return ResultUtils.success(updateRouteResult);
        } catch (Exception e) {
            log.error("更新路线失败", e);
            return ResultUtils.error(ErrorCode.OPERATION_ERROR, "更新路线失败: " + e.getMessage());
        }
    }

    /**
     * 保存或更新站点
     */
    private Long saveOrUpdateStation(RouteAllRequest.RouteStation stationRequest,
                                     List<RouteAllRequest.RouteStation> allStations,
                                     int currentIndex) {
        // 检查ID是否为null或小于等于0（包括-1）
        if (stationRequest.getId() != null && stationRequest.getId() <= 0) {
            // 如果是无效ID（包括-1），设置为null让数据库自动生成
            stationRequest.setId(null);
        }

        Station station;
        if (stationRequest.getId() == null) {
            // 新建站点
            station = new Station();
            BeanUtils.copyProperties(stationRequest, station);

            // 生成站点名称
            if (StringUtils.isBlank(station.getStationName())) {
                station.setStationName(generateStationName("S"));
            }

            stationService.save(station);
        } else {
            // 更新现有站点
            station = stationService.getById(stationRequest.getId());
            if (station == null) {
                throw new RuntimeException("站点不存在: " + stationRequest.getId());
            }

            BeanUtils.copyProperties(stationRequest, station);
            stationService.updateById(station);
        }
        return station.getId();
    }

    /**
     * 处理空字符串的辅助方法
     */
    private String getDefaultIfBlank(String value, String defaultValue) {
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        return value;
    }

    /**
     * 处理空字符串的辅助方法（返回Optional）
     */
    private Optional<String> getOptionalValue(String value) {
        return Optional.ofNullable(value).filter(v -> !v.trim().isEmpty());
    }

    /**
     * 处理站点位置信息，确保经纬度和XY坐标至少有一组有效
     * 同时处理四元数方向信息
     */
    private void processStationPosition(RouteAllRequest.RouteStation station, double localLat, double localLon,
                                        List<RouteAllRequest.RouteStation> allStations, int currentIndex) {
        try {
            // 检查XY坐标和经纬度是否都为0或空
            boolean xyEmpty = StringUtils.isBlank(station.getPositionX()) ||
                    StringUtils.isBlank(station.getPositionY());

            boolean latLonEmpty = StringUtils.isBlank(station.getLongitude()) ||
                    StringUtils.isBlank(station.getLatitude()) ||
                    "0".equals(station.getLongitude()) ||
                    "0".equals(station.getLatitude());

            // 如果两组坐标都为空或0，抛出异常
            if (xyEmpty && latLonEmpty) {
                throw new IllegalArgumentException("站点位置信息不完整，必须提供XY坐标或经纬度坐标");
            }

            // 如果XY坐标为空或0，根据经纬度计算XY坐标
            if (xyEmpty) {
                if (!Double.isFinite(localLat) || !Double.isFinite(localLon)) {
                    throw new IllegalArgumentException("Geographic origin is required to convert latitude/longitude to XY");
                }
                double longitude = Double.parseDouble(station.getLongitude());
                double latitude = Double.parseDouble(station.getLatitude());

                double[] xy = llh2xyzController.convertToLocalCoordinates(
                        localLat, localLon, latitude, longitude);

                station.setPositionX(String.valueOf(xy[0]));
                station.setPositionY(String.valueOf(xy[1]));
                station.setPositionZ("0");
            }
            // 如果经纬度为空或0，根据XY坐标计算经纬度
            else if (latLonEmpty && Double.isFinite(localLat) && Double.isFinite(localLon)) {
                double x = Double.parseDouble(station.getPositionX());
                double y = Double.parseDouble(station.getPositionY());

                double[] latLon = llh2xyzController.convertFromLocalCoordinates(
                        localLat, localLon, x, y, 0);

                station.setLatitude(String.valueOf(latLon[0]));
                station.setLongitude(String.valueOf(latLon[1]));
            }

            // 处理四元数方向信息
            processOrientation(station, allStations, currentIndex);

        } catch (Exception e) {
            log.error("处理站点位置信息失败", e);
            throw new RuntimeException("处理站点位置信息失败: " + e.getMessage());
        }
    }

    /**
     * 处理四元数方向信息
     * 如果为空则默认指向下一点方向，若为最后一点为空，则与上一点相同
     */
    private void processOrientation(RouteAllRequest.RouteStation currentStation,
                                    List<RouteAllRequest.RouteStation> allStations,
                                    int currentIndex) {
        // 检查四元数是否为空
        boolean orientationEmpty = StringUtils.isBlank(currentStation.getOrientationX()) ||
                StringUtils.isBlank(currentStation.getOrientationY()) ||
                StringUtils.isBlank(currentStation.getOrientationZ()) ||
                StringUtils.isBlank(currentStation.getOrientationW()) ||
                "0".equals(currentStation.getOrientationX()) ||
                "0".equals(currentStation.getOrientationY()) ||
                "0".equals(currentStation.getOrientationZ()) ||
                "0".equals(currentStation.getOrientationW());

        if (orientationEmpty) {
            // 如果是最后一个站点，使用上一个站点的方向
            if (currentIndex == allStations.size() - 1) {
                if (currentIndex > 0) {
                    RouteAllRequest.RouteStation prevStation = allStations.get(currentIndex - 1);
                    currentStation.setOrientationX(prevStation.getOrientationX());
                    currentStation.setOrientationY(prevStation.getOrientationY());
                    currentStation.setOrientationZ(prevStation.getOrientationZ());
                    currentStation.setOrientationW(prevStation.getOrientationW());
                } else {
                    // 如果只有一个站点，设置默认方向
                    setDefaultOrientation(currentStation);
                }
            } else {
                // 指向下一个站点的方向
                RouteAllRequest.RouteStation nextStation = allStations.get(currentIndex + 1);
                calculateDirectionToNextPoint(currentStation, nextStation);
            }
        } else {
            // 如果四元数不为空，确保所有分量都有值
            if (StringUtils.isBlank(currentStation.getOrientationX())) currentStation.setOrientationX("0");
            if (StringUtils.isBlank(currentStation.getOrientationY())) currentStation.setOrientationY("0");
            if (StringUtils.isBlank(currentStation.getOrientationZ())) currentStation.setOrientationZ("0");
            if (StringUtils.isBlank(currentStation.getOrientationW())) currentStation.setOrientationW("1");
        }
    }

    /**
     * 计算指向下一个站点的方向
     */
    private void calculateDirectionToNextPoint(RouteAllRequest.RouteStation currentStation,
                                               RouteAllRequest.RouteStation nextStation) {
        try {
            double currentX = Double.parseDouble(currentStation.getPositionX());
            double currentY = Double.parseDouble(currentStation.getPositionY());
            double nextX = Double.parseDouble(nextStation.getPositionX());
            double nextY = Double.parseDouble(nextStation.getPositionY());

            // 计算方向向量
            double dx = nextX - currentX;
            double dy = nextY - currentY;

            // 计算角度（弧度）
            double angle = Math.atan2(dy, dx);

            // 将角度转换为四元数（绕Z轴旋转）
            // 四元数公式: q = cos(θ/2) + (0, 0, sin(θ/2))
            double halfAngle = angle / 2;
            double cosHalf = Math.cos(halfAngle);
            double sinHalf = Math.sin(halfAngle);

            currentStation.setOrientationX("0");
            currentStation.setOrientationY("0");
            currentStation.setOrientationZ(String.valueOf(sinHalf));
            currentStation.setOrientationW(String.valueOf(cosHalf));

        } catch (Exception e) {
            log.warn("计算方向失败，使用默认方向", e);
            setDefaultOrientation(currentStation);
        }
    }

    /**
     * 设置默认方向（朝向前方）
     */
    private void setDefaultOrientation(RouteAllRequest.RouteStation station) {
        station.setOrientationX("0");
        station.setOrientationY("0");
        station.setOrientationZ("0");
        station.setOrientationW("1");
    }



/**********************************************天地图项目****************************************************/
    /**
     * 添加天地图路线
     */
    @Transactional(rollbackFor = Exception.class)
    @PostMapping("addMap")
    public BaseResponse<Boolean> addMapRoute(@RequestBody RouteMapRequest routeMapRequest) throws JsonProcessingException {
        log.info("routeMapRequest {}",routeMapRequest);
        // 获取路线名称
        String routeName = routeMapRequest.getRouteName();
        // 初始化站点ID列表
        List<Long> stationIds = new ArrayList<>();
        List<String> rowData = Arrays.stream(routeMapRequest.getAvoidanceArr()).toList();
        List<RouteDetail> routeDetailList = new ArrayList<>();
        log.info("routeName :" + routeName);
        // 遍历站点经纬度数据，每两个数组成员表示一个站点的经纬度
        for (int i = 0; i < routeMapRequest.getRouteMapData().length; i += 2) {
            String  longitude = routeMapRequest.getRouteMapData()[i]; // 经度
            String  latitude = routeMapRequest.getRouteMapData()[i + 1]; // 纬度

            // 创建 StationRequest 对象并赋值
            StationRequest stationRequest = new StationRequest();
            stationRequest.setLongitude(longitude);
            stationRequest.setLatitude(latitude);
            stationRequest.setStationCode(String.valueOf(0));

            // 获取最新的站点编号
            queryMapStationList();
            // 生成新的站点名，数字加1，拼接"S"
            String newStationName = prefix + (latestStationNumber + 1);
            // 设置新的站点名
            stationRequest.setStationName(newStationName);

            // 将其他属性都设置为0
            stationRequest.setPositionX("0");
            stationRequest.setPositionY("0");
            stationRequest.setPositionZ("0");
            stationRequest.setOrientationX("0");
            stationRequest.setOrientationY("0");
            stationRequest.setOrientationZ("0");
            stationRequest.setOrientationW("0");

            // 调用 StationController 中的 addStation 方法将站点存入数据库
            BaseResponse<Object> stationResponse = stationController.addMapStation(stationRequest);

            if (stationResponse.getData() == null) {
                // 如果保存站点失败，直接返回错误
                return ResultUtils.error(ErrorCode.FAILED_TO_SAVE_STATION_DATA);
            }
            log.info("stationResponse {}",stationResponse);
            // 获取并记录站点ID（从数据库获取最新的ID）
            Long stationId = (Long) stationResponse.getData(); // 获取站点的最新ID
            stationIds.add(stationId);
        }

        // 创建 Route 对象并保存
        Route route = new Route();

        // 创建 ObjectMapper 实例
        ObjectMapper objectMapper = new ObjectMapper();

        // 将 stationIds 转换为 JSON 数组格式
        String stationIdsString = objectMapper.writeValueAsString(stationIds);

        route.setStationIds(stationIdsString);
        route.setRouteName(routeName);
        route.setMapCoverage(routeMapRequest.getMapCoverage());
        route.setRoutesource(routeMapRequest.getRoutesource());

        boolean saveRoute = routeService.save(route);
        Long routeId = route.getId();
        // 2. 构建 RouteDetail 列表
        for (int i = 0; i < stationIds.size(); i++) {
            RouteDetail detail = new RouteDetail();
            detail.setRouteId(routeId);
            detail.setStationId(stationIds.get(i));
            detail.setArea(rowData.get(i));
            routeDetailList.add(detail);
        }
        // 3. 批量保存 RouteDetail
        boolean saveRouteDeail = routeDetailService.saveBatch(routeDetailList);
        return ResultUtils.success(saveRoute && saveRouteDeail);
    }

    /**
     * 查询天地图所有路线
     */
    @GetMapping("querymapall")
    public BaseResponse<List<Map<String, Object>>> queryMapRouteList() {
        QueryWrapper<Route> queryWrapper = new QueryWrapper<>();
        queryWrapper.likeRight("routesource", "0");
        List<Route> routeList = routeService.list(queryWrapper);

        // 如果没有查询到路线，返回失败响应
        if (routeList == null || routeList.isEmpty()) {
            return ResultUtils.success(new ArrayList<>());
        }

        // 存储最终返回的数据
        List<Map<String, Object>> result = new ArrayList<>();

        for (Route route : routeList) {
            List<Long> stationIdList = JSON.parseArray(route.getStationIds(), Long.class);
            List<Station> stationList = new ArrayList<>();
            List<RouteDetail> routeDetailList = new ArrayList<>();
            log.info("路线 {}: 站点列表大小 {}, 数据: {}", route.getId(), stationIdList.size(), stationIdList);

            // 构建路线数据
            Map<String, Object> routeData = new HashMap<>();
            routeData.put("routeId", route.getId());
            routeData.put("routeName", route.getRouteName());
            routeData.put("mapCoverage", route.getMapCoverage());
            routeData.put("routesource",route.getRoutesource());

            for (Long stationId : stationIdList) {
                QueryWrapper<Station> stationQueryWrapper = new QueryWrapper<>();
                stationQueryWrapper.eq("id", stationId);
                Station station = stationMapper.selectOne(stationQueryWrapper);
                stationList.add(station);

                QueryWrapper<RouteDetail> routeDetailQueryWrapper = new QueryWrapper<>();
                routeDetailQueryWrapper.eq("routeId", route.getId()).eq("stationId",stationId);
                RouteDetail routeDetail = routeDetailMapper.selectOne(routeDetailQueryWrapper);
                routeDetailList.add(routeDetail);
            }
            // 存储站点数据
            List<Map<String, Object>> stations = new ArrayList<>();
            for (int i = 0; i < stationIdList.size(); i++) {
                Station station = stationList.get(i);
                RouteDetail routeDetail = routeDetailList.get(i);
                Map<String, Object> stationData = new HashMap<>();
                stationData.put("stationId", station.getId());
                stationData.put("stationName", station.getStationName());
                stationData.put("getPositionX", station.getPositionX());
                stationData.put("getPositionY", station.getPositionY());
                stationData.put("longitude", station.getLongitude());
                stationData.put("latitude", station.getLatitude());
                stationData.put("area",routeDetail.getArea());
                stations.add(stationData);
            }
            routeData.put("stations", stations);
            result.add(routeData);
        }

        // 返回结果
        return ResultUtils.success(result);
    }

    @PostMapping("updateMap")
    @Transactional(rollbackFor = Exception.class)
    public BaseResponse<Boolean> updateMapRoute(@RequestBody RouteMixRequest routeMixRequest) throws JsonProcessingException {

        String routeName = routeMixRequest.getRouteName();
        List<Long> stationIds = new ArrayList<>();
        List<String> rowData = Arrays.stream(routeMixRequest.getRowData()).toList();
        List<RouteDetail> routeDetailList = new ArrayList<>();

        for (StationRequest station : routeMixRequest.getStations()) {
            Long stationId = station.getId();
            if (stationId == null || stationId == -1) {
                // 创建新站点
                StationRequest newStation = new StationRequest();
                newStation.setLongitude(station.getLongitude());
                newStation.setLatitude(station.getLatitude());
                newStation.setPositionX("0");
                newStation.setPositionY("0");
                newStation.setPositionZ("0");
                newStation.setOrientationX("0");
                newStation.setOrientationY("0");
                newStation.setOrientationZ("0");
                newStation.setOrientationW("0");
                newStation.setStationCode(String.valueOf(0));

                // 生成新站点名称
                queryMapStationList(); // 确保此方法更新latestStationNumber
                String newStationName = prefix + (latestStationNumber + 1);
                newStation.setStationName(newStationName);

                // 保存新站点并获取ID
                BaseResponse<Object> response = stationController.addMapStation(newStation);
                if (response.getData() == null) {
                    return ResultUtils.error(ErrorCode.FAILED_TO_SAVE_STATION_DATA);
                }
                Long newId = (Long) response.getData();
                stationIds.add(newId);
            } else {
                // 使用现有站点ID
                stationIds.add(stationId);
            }
        }

        // 1. 保存 Route 并获取 routeId
        Route route = new Route();
        route.setId(routeMixRequest.getId());
        route.setRouteName(routeName);
        route.setMapCoverage(routeMixRequest.getMapCoverage());
        route.setRoutesource(routeMixRequest.getRoutesource());
        ObjectMapper objectMapper = new ObjectMapper();
        route.setStationIds(objectMapper.writeValueAsString(stationIds));
        boolean updateRoute = routeService.updateById(route);
        Long routeId = route.getId();
        boolean deleteExistingRecords = routeDetailService.remove(new QueryWrapper<RouteDetail>().eq("routeId", routeId));
        // 2. 构建 RouteDetail 列表
        for (int i = 0; i < stationIds.size(); i++) {
            RouteDetail detail = new RouteDetail();
            detail.setRouteId(routeId);
            detail.setStationId(stationIds.get(i));
            detail.setArea(rowData.get(i));
            routeDetailList.add(detail);
        }

        // 3. 批量保存 RouteDetail
        boolean saveRouteDeail = routeDetailService.saveBatch(routeDetailList);
        return ResultUtils.success(deleteExistingRecords && updateRoute && saveRouteDeail);

    }
    /**
     * 根据路线id查询路线数据发送给车辆
     */
    @GetMapping("detailmap/{id}")
    @Transactional(rollbackFor = Exception.class)
    public  BaseResponse<List<Object>> querymapRouteDetailData(@PathVariable("id") Long id) {
        return buildPathPointData(id);
    }
    /*********************************融合地图***************************************/
    @PostMapping("addMix")
    public BaseResponse<Boolean> addMixRoute(@RequestBody RouteMixRequest routeMixRequest) throws JsonProcessingException {
        log.info("routeMixRequest {}", routeMixRequest);
        int rpwData_Flag = 0;
        String routeName = routeMixRequest.getRouteName();
        List<Long> stationIds = new ArrayList<>();
        List<String> rowData = Arrays.stream(routeMixRequest.getRowData()).toList();
        List<RouteDetail> routeDetailList = new ArrayList<>();
        List<StationRequest> stations = routeMixRequest.getStations();

        // 获取本地坐标系原点经纬度
        QueryWrapper<Param> paramQueryWrapper = new QueryWrapper<>();
        Param param = paramMapper.selectOne(paramQueryWrapper);
        double localLat = Double.parseDouble(param.getLocal_origin_latitude());
        double localLon = Double.parseDouble(param.getLocal_origin_longitude());

        // 预计算所有站点的XY坐标
        List<double[]> xyCoordinates = new ArrayList<>();
        for (StationRequest station : stations) {
            double longitude = Double.parseDouble(station.getLongitude());
            double latitude = Double.parseDouble(station.getLatitude());
            double[] xy = llh2xyzController.convertToLocalCoordinates(localLat, localLon, latitude, longitude);
            xyCoordinates.add(xy);
        }

        for (int i = 0; i < stations.size(); i++) {
            StationRequest station = stations.get(i);
            Long stationId = station.getId();

            // 计算航向角（yaw）和四元数
            double yaw = 0; // 默认航向角为0（最后一个点）
            double[] quaternion = {0, 0, 0, 1}; // 默认四元数（无旋转）

            if (i < stations.size() - 1) {
                // 计算当前点到下一个点的方向向量
                double[] currentXY = xyCoordinates.get(i);
                double[] nextXY = xyCoordinates.get(i + 1);
                double dx = nextXY[0] - currentXY[0];
                double dy = nextXY[1] - currentXY[1];

                // 计算航向角（弧度），atan2返回的是从X轴正方向到向量的角度
                yaw = Math.atan2(dy, dx);

                // 将航向角转换为四元数（仅绕Z轴旋转）
                quaternion = calculateQuaternionFromYaw(yaw);
            }

            if (stationId == null || stationId == -1) {
                // 创建新站点
                StationRequest newStation = new StationRequest();
                newStation.setLongitude(station.getLongitude());
                newStation.setLatitude(station.getLatitude());
                newStation.setPositionX(String.valueOf(xyCoordinates.get(i)[0]));
                newStation.setPositionY(String.valueOf(xyCoordinates.get(i)[1]));
                newStation.setPositionZ("0");
                newStation.setOrientationX(String.valueOf(quaternion[0]));
                newStation.setOrientationY(String.valueOf(quaternion[1]));
                newStation.setOrientationZ(String.valueOf(quaternion[2]));
                newStation.setOrientationW(String.valueOf(quaternion[3]));
                newStation.setStationCode(String.valueOf(0));

                // 生成新站点名称
                queryMapStationList();
                String newStationName = prefix + (latestStationNumber + 1);
                newStation.setStationName(newStationName);

                // 保存新站点并获取ID
                BaseResponse<Object> response = stationController.addMapStation(newStation);
                if (response.getData() == null) {
                    return ResultUtils.error(ErrorCode.FAILED_TO_SAVE_STATION_DATA);
                }
                Long newId = (Long) response.getData();
                stationIds.add(newId);
            } else {
                // 使用现有站点ID
                stationIds.add(stationId);
            }
        }

        // 保存Route和RouteDetail的逻辑保持不变...
        Route route = new Route();
        route.setRouteName(routeName);
        route.setMapCoverage(routeMixRequest.getMapCoverage());
        route.setRoutesource(routeMixRequest.getRoutesource());
        route.setSpeed(routeMixRequest.getSpeed());
        ObjectMapper objectMapper = new ObjectMapper();
        route.setStationIds(objectMapper.writeValueAsString(stationIds));
        boolean saveRoute = routeService.save(route);
        Long routeId = route.getId();

        for (int i = 0; i < stationIds.size(); i++) {
            RouteDetail detail = new RouteDetail();
            detail.setRouteId(routeId);
            detail.setStationId(stationIds.get(i));
            detail.setArea(rowData.get(i));
            routeDetailList.add(detail);
        }

        boolean saveRouteDeail = routeDetailService.saveBatch(routeDetailList);
        return ResultUtils.success(saveRoute && saveRouteDeail);
    }




    @LogAnnotation(title = "路线模块", content = "更新路线")
    @PostMapping("updateMix")
    public BaseResponse<Boolean> updateRoute(@RequestBody RouteMixRequest routeRequest) {
        log.info("routeRequest:{}",routeRequest);
        // 第一步：根据请求中的 ID 从数据库获取现有路线
        Route existingRoute = routeService.getById(routeRequest.getId());

        // 第二步：将现有路线更新为请求中的新值
        existingRoute.setRouteName(routeRequest.getRouteName());
        existingRoute.setMapCoverage(routeRequest.getMapCoverage());
        existingRoute.setRoutesource(routeRequest.getRoutesource());

        List<StationRequest> newStations = routeRequest.getStations();

        if (newStations != null) {
            // 遍历每个 StationRequest 对象并进行更新
            for (StationRequest stationRequest : newStations) {
                BaseResponse<Boolean> updateResponse = stationController.updateStation(stationRequest);
            }
        }
        // 第三步：将更新后的路线保存回数据库
        boolean updateSuccess = routeService.updateById(existingRoute);

        return ResultUtils.success(updateSuccess);
    }

    /*********************************自动规划***************************************/
    @PostMapping("addAuto")
    public BaseResponse<Boolean> addAutoRoute(@RequestBody RouteAutoRequest routeRequest) throws JsonProcessingException {
        log.info("routeMixRequest {}", routeRequest);
        String routeName = routeRequest.getRouteName();
        List<Long> stationIds = new ArrayList<>();
        List<String> rowData = Arrays.stream(routeRequest.getRowData()).toList();
        List<RouteDetail> routeDetailList = new ArrayList<>();

        for (StationRequest station : routeRequest.getStations()) {
            Long stationId = station.getId();
            // 创建新站点
            StationRequest newStation = new StationRequest();
            newStation.setLongitude(station.getLongitude());
            newStation.setLatitude(station.getLatitude());
            newStation.setPositionX("0");
            newStation.setPositionY("0");
            newStation.setStationCode(String.valueOf(1));
            // 生成新站点名称
            queryMapStationList();
            String newStationName = Auto + (latestAutoNumber + 1);
            newStation.setStationName(newStationName);
            // 保存新站点并获取ID
            BaseResponse<Object> response = stationController.addMapStation(newStation);
            if (response.getData() == null) {
                return ResultUtils.error(ErrorCode.FAILED_TO_SAVE_STATION_DATA);
            }
            Long newId = (Long) response.getData();
            stationIds.add(newId);
        }

        // 1. 保存 Route 并获取 routeId
        Route route = new Route();
        route.setRouteName(routeName);
        route.setMapCoverage(routeRequest.getMapCoverage());
        route.setRoutesource(routeRequest.getRoutesource());

        ObjectMapper objectMapper = new ObjectMapper();
        route.setStationIds(objectMapper.writeValueAsString(stationIds));
        boolean saveRoute = routeService.save(route);
        Long routeId = route.getId();
        // 2. 构建 RouteDetail 列表
        for (int i = 0; i < stationIds.size(); i++) {
            RouteDetail detail = new RouteDetail();
            detail.setRouteId(routeId);
            detail.setStationId(stationIds.get(i));
            detail.setArea(rowData.get(i));
            routeDetailList.add(detail);
        }
        log.info("routeDetailList{}",routeDetailList);
        // 3. 批量保存 RouteDetail
        boolean saveRouteDeail = routeDetailService.saveBatch(routeDetailList);

        /***********************************保存路线边界********************************/
        // 获取路线名称
        String routeName_new = Edge + routeRequest.getRouteName();
        // 初始化站点ID列表
        List<Long> stationIds_new = new ArrayList<>();
        List<RouteDetail> routeDetailList_new = new ArrayList<>();
        log.info("routeRequest.getRouteAutoData() {}",routeRequest.getRouteAutoData());
        // 遍历站点经纬度数据，每两个数组成员表示一个站点的经纬度
        for (int i = 0; i < routeRequest.getRouteAutoData().length; i += 2) {
            String  longitude = routeRequest.getRouteAutoData()[i]; // 经度
            String  latitude = routeRequest.getRouteAutoData()[i + 1]; // 纬度

            // 创建 StationRequest 对象并赋值
            StationRequest stationRequest = new StationRequest();
            stationRequest.setLongitude(longitude);
            stationRequest.setLatitude(latitude);
            stationRequest.setPositionX("0");
            stationRequest.setPositionY("0");
            stationRequest.setStationCode(String.valueOf(1));

            // 获取最新的站点编号
            queryMapStationList();
            String newStationName = Edge + (latestEdgeNumber + 1);
            stationRequest.setStationName(newStationName);
            BaseResponse<Object> stationResponse = stationController.addMapStation(stationRequest);

            if (stationResponse.getData() == null) {
                // 如果保存站点失败，直接返回错误
                return ResultUtils.error(ErrorCode.FAILED_TO_SAVE_STATION_DATA);
            }
            log.info("stationResponse {}",stationResponse);
            Long stationId = (Long) stationResponse.getData(); // 获取站点的最新ID
            stationIds_new.add(stationId);
        }
        // 创建 Route 对象并保存
        Route route_new = new Route();
        ObjectMapper objectMapper_new = new ObjectMapper();
        // 将 stationIds 转换为 JSON 数组格式
        String stationIdsString = objectMapper_new.writeValueAsString(stationIds_new);

        route_new.setStationIds(stationIdsString);
        route_new.setRouteName(routeName_new);
        route_new.setMapCoverage(routeRequest.getMapCoverage());
        route_new.setRoutesource(routeRequest.getRoutesource());

        boolean saveRoutenew = routeService.save(route_new);
        Long routeId_new = route_new.getId();
        // 2. 构建 RouteDetail 列表
        for (int i = 0; i < stationIds_new.size(); i++) {
            RouteDetail detail = new RouteDetail();
            detail.setRouteId(routeId_new);
            detail.setStationId(stationIds_new.get(i));
            detail.setArea(String.valueOf(1));
            routeDetailList_new.add(detail);
        }
        // 3. 批量保存 RouteDetail
        boolean saveRouteDeail_new = routeDetailService.saveBatch(routeDetailList_new);
        return ResultUtils.success(saveRoute && saveRouteDeail);
    }

    /**
     * 删除自动规划路线
     */
    @LogAnnotation(title = "路线模块", content = "删除路线")
    @DeleteMapping("deleteauto/{id}")
    public BaseResponse<Boolean> deleteAutoRoute(@PathVariable("id") Long id) {
        boolean delete = routeService.removeById(id);
        boolean delete_new = routeService.removeById((id + 1 ));
        return ResultUtils.success(delete && delete_new);
    }

    @PostMapping("save")
    public BaseResponse<Boolean> saveRoute(@RequestBody RouteRequest request) {
        try {
            Route route = saveOrUpdateRoute(request);
            return ResultUtils.success(true);
        } catch (Exception e) {
            log.error("保存路线失败", e);
            return ResultUtils.error(ErrorCode.OPERATION_ERROR, "保存路线失败");
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public Route saveOrUpdateRoute(RouteRequest request) throws JsonProcessingException {
        // 1. 处理Route实体
        Route route = request.getId() != null ? getById(request.getId(), Route.class) : new Route();
        BeanUtils.copyProperties(request, route);

        // 2. 处理站点
        List<Long> stationIds = processStations(request);
        route.setStationIds(new ObjectMapper().writeValueAsString(stationIds));

        // 3. 保存或更新Route
        saveOrUpdate(route);

        if (request.getRouteAutoData() != null) {

            List<Long> stationIds_new = new ArrayList<>();
            List<RouteDetail> routeDetailList_new = new ArrayList<>();
            // 自动规划边界模式
            for (int i = 0; i < request.getRouteAutoData().length; i += 2) {

                StationRequest stationRequest = createStationRequestFromMapData(request, i);
                Long stationId = processSingleStation(stationRequest, "1");
                stationIds_new.add(stationId);
            }
            Route route_new = new Route();
            ObjectMapper objectMapper_new = new ObjectMapper();
            // 将 stationIds 转换为 JSON 数组格式
            String stationIdsString = objectMapper_new.writeValueAsString(stationIds_new);

            route_new.setStationIds(stationIdsString);
            route_new.setRouteName("Edge" + request.getRouteName());
            route_new.setMapCoverage(request.getMapCoverage());
            route_new.setRoutesource(request.getRoutesource());

            Long routeId_new = route_new.getId();

            for (int i = 0; i < stationIds_new.size(); i++) {
                RouteDetail detail = new RouteDetail();
                detail.setRouteId(routeId_new);
                detail.setStationId(stationIds_new.get(i));
                detail.setArea(String.valueOf(1));
                routeDetailList_new.add(detail);
            }
        }

        // 4. 处理RouteDetail
        processRouteDetails(route.getId(), stationIds, request.getRowData());

        return route;
    }

    private List<Long> processStations(RouteRequest request) {
        List<Long> stationIds = new ArrayList<>();

        // 处理不同类型的站点数据
        if (request.getStations() != null) {
            // 融合地图或自动规划模式
            for (StationRequest station : request.getStations()) {
                Long stationId = processSingleStation(station, request.getRoutesource());
                stationIds.add(stationId);
            }
        } else if (request.getRouteMapData() != null) {
            // 天地图模式
            for (int i = 0; i < request.getRouteMapData().length; i += 2) {
                StationRequest stationRequest = createStationRequestFromMapData(request, i);
                Long stationId = processSingleStation(stationRequest, "0");
                stationIds.add(stationId);
            }
        } else if (request.getRouteAutoData() != null) {
            // 自动规划边界模式
            for (int i = 0; i < request.getRouteAutoData().length; i += 2) {
                StationRequest stationRequest = createStationRequestFromMapData(request, i);
                Long stationId = processSingleStation(stationRequest, "1");
                stationIds.add(stationId);
            }
        }

        return stationIds;
    }

    private Long processSingleStation(StationRequest stationRequest, String routeSource) {
        if (stationRequest.getId() == null || stationRequest.getId() == -1) {
            // 新站点
            stationRequest.setStationCode(routeSource);
            if (stationRequest.getStationName() == null) {
                String prefix = "1".equals(routeSource) ? "Auto" : "S";
                stationRequest.setStationName(generateStationName(prefix));
            }
            return (Long) stationController.addStation(stationRequest).getData();
        }
        return stationRequest.getId();
    }

    private void processRouteDetails(Long routeId, List<Long> stationIds, String[] rowData) {
        // 删除现有详情
        remove(new QueryWrapper<RouteDetail>().eq("routeId", routeId));

        // 添加新详情
        if (rowData != null && rowData.length > 0) {
            List<RouteDetail> details = new ArrayList<>();
            for (int i = 0; i < stationIds.size(); i++) {
                RouteDetail detail = new RouteDetail();
                detail.setRouteId(routeId);
                detail.setStationId(stationIds.get(i));
                if (i < rowData.length) {
                    detail.setArea(rowData[i]);
                } else {
                    detail.setArea("0"); // 默认值
                }
                details.add(detail);
            }
            saveBatch(details);
        }
    }


    @GetMapping("query/{routesource}")
    public BaseResponse<List<Map<String, Object>>> queryRouteList(@PathVariable String routesource) {
        // 1. 验证routesource参数
        if (!"0".equals(routesource) && !"1".equals(routesource)) {
            return ResultUtils.error(ErrorCode.PARAMS_ERROR, "Invalid routesource parameter");
        }

        // 2. 查询符合条件的路线列表
        QueryWrapper<Route> queryWrapper = new QueryWrapper<>();
        queryWrapper.likeRight("routesource", routesource);
        List<Route> routeList = routeService.list(queryWrapper);

        // 3. 如果没有数据，返回空列表
        if (CollectionUtils.isEmpty(routeList)) {
            return ResultUtils.success(Collections.emptyList());
        }

        // 4. 根据routesource选择处理方式
        if ("0".equals(routesource)) {
            return handleMapRoutes(routeList);
        } else {
            return handleAutoRoutes(routeList);
        }
    }

    /**
     * 处理地图路线数据（原querymapall逻辑）
     */
    private BaseResponse<List<Map<String, Object>>> handleMapRoutes(List<Route> routeList) {
        List<Map<String, Object>> result = new ArrayList<>();

        for (Route route : routeList) {
            Map<String, Object> routeData = convertRouteToMap(route);
            result.add(routeData);
        }

        return ResultUtils.success(result);
    }

    /**
     * 处理自动路线数据
     */
    private BaseResponse<List<Map<String, Object>>> handleAutoRoutes(List<Route> routeList) {
        List<Map<String, Object>> result = new ArrayList<>();

        // 每次处理两条路线（i和i+1）
        for (int i = 0; i < routeList.size(); i += 2) {
            // 处理主路线（第i条）
            Route mainRoute = routeList.get(i);
            Map<String, Object> routeData = convertRouteToMap(mainRoute);

            // 如果有下一条路线（i+1存在）
            if (i + 1 < routeList.size()) {
                Route edgeRoute = routeList.get(i + 1);
                List<Map<String, Object>> edgeStations = getStationsForRoute(edgeRoute);
                // 将第二条路线的站点作为edges加入第一条路线
                routeData.put("edges", edgeStations);
            }

            result.add(routeData);
        }

        return ResultUtils.success(result);
    }

    /**
     * 将Route对象转换为包含基本信息的Map
     */
    private Map<String, Object> convertRouteToMap(Route route) {
        Map<String, Object> routeData = new HashMap<>();
        routeData.put("routeId", route.getId());
        routeData.put("routeName", route.getRouteName());
        routeData.put("mapCoverage", route.getMapCoverage());
        routeData.put("routesource", route.getRoutesource());

        // 获取并添加站点信息
        List<Map<String, Object>> stations = getStationsForRoute(route);
        routeData.put("stations", stations);

        return routeData;
    }

    /**
     * 获取路线对应的所有站点信息
     */
    private List<Map<String, Object>> getStationsForRoute(Route route) {
        List<Long> stationIdList = JSON.parseArray(route.getStationIds(), Long.class);
        List<Map<String, Object>> stations = new ArrayList<>();

        for (Long stationId : stationIdList) {
            // 查询站点基本信息
            Station station = stationMapper.selectOne(new QueryWrapper<Station>().eq("id", stationId));

            // 查询路线-站点关联信息
            RouteDetail routeDetail = routeDetailMapper.selectOne(
                    new QueryWrapper<RouteDetail>()
                            .eq("routeId", route.getId())
                            .eq("stationId", stationId));

            // 构建站点数据
            Map<String, Object> stationData = new HashMap<>();
            stationData.put("stationId", station.getId());
            stationData.put("stationName", station.getStationName());
            stationData.put("getPositionX", station.getPositionX());
            stationData.put("getPositionY", station.getPositionY());
            stationData.put("longitude", station.getLongitude());
            stationData.put("latitude", station.getLatitude());
            stationData.put("area", 0/*routeDetail.getArea()*/);

            stations.add(stationData);
        }

        return stations;
    }

    /************************************室内导航*********************************************/
    /**
     * 查询并处理同一routesource下的多条路线，拼接为一条路线并简化站点信息
     */
    @GetMapping("queryros/{routeGroup}")
    public BaseResponse<List<Map<String, Object>>> queryRosRouteList(@PathVariable String routeGroup) {
        // 1. 查询符合条件的路线列表
        QueryWrapper<Route> queryWrapper = new QueryWrapper<>();
        queryWrapper.likeRight("routeGroup", routeGroup);
        List<Route> routeList = routeService.list(queryWrapper);

        // 2. 如果没有数据，返回空列表
        if (CollectionUtils.isEmpty(routeList)) {
            return ResultUtils.success(Collections.emptyList());
        }

        // 3. 使用第一条路线作为基础信息
        Route firstRoute = routeList.get(0);
        Map<String, Object> mergedRoute = new HashMap<>();
        mergedRoute.put("routeId", firstRoute.getId());
        mergedRoute.put("routeName", firstRoute.getRouteName());
        mergedRoute.put("mapName", firstRoute.getMapName());
        mergedRoute.put("routeGroup", firstRoute.getRouteGroup());

        // 4. 合并所有路线的站点信息（只保留XY坐标）
        List<Map<String, Object>> mergedStations = new ArrayList<>();
        for (Route route : routeList) {
            List<Long> stationIdList = JSON.parseArray(route.getStationIds(), Long.class);

            for (Long stationId : stationIdList) {
                Station station = stationMapper.selectOne(
                        new QueryWrapper<Station>().eq("id", stationId));

                Map<String, Object> stationData = new HashMap<>();
                stationData.put("positionX", station.getPositionX());
                stationData.put("positionY", station.getPositionY());
                mergedStations.add(stationData);
            }
        }
        mergedRoute.put("stations", mergedStations);

        // 5. 返回合并后的单一路线
        return ResultUtils.success(Collections.singletonList(mergedRoute));
    }

    /**
     * 根据地图区域查询路线数据（按routesource分组）
     * 调用示例：GET /route/map-coverage/A区
     */
    @GetMapping("/map-coverage/{mapName}")
    public BaseResponse<List<Map<String, Object>>> getRoutesByMapCoverage(@PathVariable String mapName) {
        // 1. 查询指定mapCoverage下的所有路线（按routeGroup数值排序）
        List<Route> routes = routeService.list(
                new QueryWrapper<Route>()
                        .eq("mapName", mapName)
                        .orderByAsc("CAST(routeGroup AS SIGNED)")); // 按routeGroup数值升序排序

        if (CollectionUtils.isEmpty(routes)) {
            return ResultUtils.success(Collections.emptyList());
        }

        // 2. 批量获取所有站点信息
        Set<Long> stationIds = routes.stream()
                .flatMap(r -> JSON.parseArray(r.getStationIds(), Long.class).stream())
                .collect(Collectors.toSet());

        Map<Long, Station> stationMap = stationService.listByIds(stationIds).stream()
                .collect(Collectors.toMap(Station::getId, Function.identity()));

        // 3. 批量获取所有路线详情信息
        List<Long> routeIds = routes.stream().map(Route::getId).collect(Collectors.toList());
        Map<Long, List<RouteDetail>> routeDetailsMap = routeDetailService.list(
                new QueryWrapper<RouteDetail>().in("routeId", routeIds)
        ).stream().collect(Collectors.groupingBy(RouteDetail::getRouteId));

        // 4. 按routeGroup分组处理
        Map<String, List<Route>> groupedRoutes = routes.stream()
                .collect(Collectors.groupingBy(Route::getRouteGroup));

        // 5. 构建返回结果（按routeGroup数值排序）
        List<Map<String, Object>> result = groupedRoutes.entrySet().stream()
                .sorted(Comparator.comparingInt(entry -> Integer.parseInt(entry.getKey()))) // 按routeGroup数值排序
                .map(entry -> {
                    String routeGroup = entry.getKey();
                    List<Route> routeGroupList = entry.getValue();

                    Map<String, Object> routeData = new LinkedHashMap<>();
                    routeData.put("mapName", mapName);
                    routeData.put("routeGroup", routeGroupList); // 包含完整路线信息的列表

                    // 使用第一条路线的routeName作为代表
                    if (!routeGroupList.isEmpty()) {
                        routeData.put("routeName", routeGroupList.get(0).getRouteName());
                    }

                    // 合并该routeGroup下的所有站点
                    List<Map<String, String>> stations = routeGroupList.stream()
                            .flatMap(r -> {
                                List<Long> rStationIds = JSON.parseArray(r.getStationIds(), Long.class);
                                List<RouteDetail> details = routeDetailsMap.getOrDefault(r.getId(), Collections.emptyList());

                                // 创建站点ID到RouteDetail的映射
                                Map<Long, RouteDetail> detailMap = details.stream()
                                        .collect(Collectors.toMap(RouteDetail::getStationId, Function.identity()));

                                return rStationIds.stream().map(stationId -> {
                                    Station s = stationMap.get(stationId);
                                    RouteDetail detail = detailMap.get(stationId);

                                    Map<String, String> station = new LinkedHashMap<>();
                                    station.put("positionX", s.getPositionX());
                                    station.put("positionY", s.getPositionY());
                                    station.put("stationid", String.valueOf(s.getId()));
                                    station.put("stationName",s.getStationName());

                                    // 添加RouteDetail信息，如果为空则设为"0"
                                    station.put("area", detail != null && detail.getArea() != null ? detail.getArea() : "0");
                                    station.put("speed", detail != null && detail.getSpeed() != null ? detail.getSpeed() : "0");
                                    station.put("direction", detail != null && detail.getDirection() != null ? detail.getDirection() : "0");
                                    station.put("action", detail != null && detail.getAction() != null ? detail.getAction() : "0");
                                    station.put("runmode", detail != null && detail.getRunmode() != null ? detail.getRunmode() : "0");
                                    station.put("stopTime", detail != null && detail.getStopTime() != null ? detail.getStopTime() : "0");
                                    station.put("position", detail != null && detail.getPosition() != null ? detail.getPosition() : "0");
                                    station.put("stop", detail != null && detail.getStop() != null ? detail.getStop() : "0");
                                    station.put("lanechange", detail != null && detail.getLanechange() != null ? detail.getLanechange() : "0");

                                    return station;
                                });
                            })
                            .collect(Collectors.toList());

                    routeData.put("stations", stations);
                    return routeData;
                })
                .collect(Collectors.toList());

        return ResultUtils.success(result);
    }

    /**
     * 根据routesource删除路线
     */
    @LogAnnotation(title = "路线模块", content = "根据routeGroup删除路线")
    @Transactional(rollbackFor = Exception.class)
    @DeleteMapping("deleteByRoutesource/{routeGroup}")
    public BaseResponse<Boolean> deleteByRoutesource(@PathVariable("routeGroup") String routeGroup) {
        // 1. 查询所有符合条件的路线ID
        QueryWrapper<Route> routeQueryWrapper = new QueryWrapper<>();
        routeQueryWrapper.eq("routeGroup", routeGroup);
        List<Route> routes = routeService.list(routeQueryWrapper);

        if (CollectionUtils.isEmpty(routes)) {
            log.warn("未找到routeGroup为[{}]的路线", routeGroup);
            return ResultUtils.success(true); // 没有数据可删，返回成功
        }

        List<Long> routeIds = routes.stream()
                .map(Route::getId)
                .collect(Collectors.toList());

        // 2. 批量删除路线详情
        QueryWrapper<RouteDetail> detailWrapper = new QueryWrapper<>();
        detailWrapper.in("routeId", routeIds);
        boolean deleteDetails = routeDetailService.remove(detailWrapper);

        // 3. 批量删除路线
        boolean deleteRoutes = routeService.removeByIds(routeIds);

        log.info("删除routesource[{}]的路线成功，删除路线数量:{}，详情数量:{}",
                routeGroup, routes.size(), routeIds.size());

        return ResultUtils.success(deleteDetails && deleteRoutes);
    }

    /**
     * 修改路线名称（室内地图专用）
     */
    @LogAnnotation(title = "路线模块", content = "修改路线名称")
    @PostMapping("updateName/{id}")
    public BaseResponse<Boolean> updateRouteName(
            @PathVariable("id") Long routeId,
            @RequestBody Map<String, String> requestBody) {

        // 验证请求参数
        String newRouteName = requestBody.get("newRouteName");
        if (routeId == null || newRouteName == null || newRouteName.trim().isEmpty()) {
            return ResultUtils.error(ErrorCode.PARAMS_ERROR, "参数错误：路线ID和名称不能为空");
        }

        // 查询路线是否存在
        Route route = routeService.getById(routeId);
        if (route == null) {
            return ResultUtils.error(ErrorCode.NULL_ERROR, "路线不存在");
        }

        try {
            // 更新路线名称
            route.setRouteName(newRouteName.trim());
            boolean isUpdated = routeService.updateById(route);

            return ResultUtils.success(isUpdated);
        } catch (Exception e) {
            log.error("修改路线名称失败", e);
            return ResultUtils.error(ErrorCode.OPERATION_ERROR, "更新失败");
        }
    }


    /*******************************************辅助函数**************************************************/
    /**
     * 按机器人硬件协议构建 path_point 数据。
     *
     * <p>协议格式为：data[0] = N，随后每个路径点固定 9 个字段：</p>
     * <pre>
     * [x, y, targetYaw(rad), pointId, speed(m/s), runMode,
     *  locationMode, reserved(0), workDuration(s)]
     * </pre>
     *
     * <p>路线详情接口和地图/PLC任务接口必须共用该方法，避免同一条路线
     * 在不同调用链路中生成不同的路径点协议。</p>
     */
    private BaseResponse<List<Object>> buildPathPointData(Long routeId) {
        if (routeId == null || routeId <= 0) {
            return ResultUtils.error(400, "路线ID不能为空且必须大于0");
        }

        try {
            Route route = routeMapper.selectOne(new QueryWrapper<Route>().eq("id", routeId));
            if (route == null) {
                return ResultUtils.error(ErrorCode.NULL_ERROR, "路线不存在");
            }

            List<Long> stationIdList = JSON.parseArray(route.getStationIds(), Long.class);
            if (CollectionUtils.isEmpty(stationIdList)) {
                return ResultUtils.error(404, "路线没有可发布的路径点");
            }

            Param param = paramMapper.selectOne(new QueryWrapper<Param>());
            String defaultSpeedValue = param != null ? param.getSpeed_run() : null;
            double defaultSpeed = parsePathPointDouble(defaultSpeedValue, 0.1, "默认速度");
            String routeSpeedValue = StringUtils.isNotBlank(route.getSpeed())
                    && !"0".equals(route.getSpeed()) ? route.getSpeed() : null;
            double routeSpeed = routeSpeedValue != null
                    ? parsePathPointDouble(routeSpeedValue, defaultSpeed, "路线速度")
                    : defaultSpeed;

            List<Object> finalData = new ArrayList<>(PATH_POINT_HEADER_SIZE
                    + stationIdList.size() * PATH_POINT_FIELD_COUNT);
            finalData.add(stationIdList.size());

            for (int index = 0; index < stationIdList.size(); index++) {
                Long stationId = stationIdList.get(index);
                Station station = stationMapper.selectOne(
                        new QueryWrapper<Station>().eq("id", stationId));
                if (station == null) {
                    return ResultUtils.error(404, "路线第" + (index + 1) + "个路径点对应的站点不存在: " + stationId);
                }

                RouteDetail routeDetail = findPathPointRouteDetail(routeId, stationId);
                double x = parsePathPointDouble(station.getPositionX(), 0.0,
                        "第" + (index + 1) + "个路径点X坐标");
                double y = parsePathPointDouble(station.getPositionY(), 0.0,
                        "第" + (index + 1) + "个路径点Y坐标");
                double targetYaw = resolvePathPointYaw(routeDetail, station, index);
                double speed = StringUtils.isNotBlank(routeDetail.getSpeed())
                        && !"0".equals(routeDetail.getSpeed())
                        ? parsePathPointDouble(routeDetail.getSpeed(), routeSpeed,
                        "第" + (index + 1) + "个路径点速度")
                        : routeSpeed;
                int runMode = parsePathPointInt(routeDetail.getRunmode(), 0,
                        "第" + (index + 1) + "个路径点运行模式");
                if (runMode != 0 && runMode != 1) {
                    throw new IllegalArgumentException("第" + (index + 1)
                            + "个路径点运行模式只能是0（追踪）或1（自转）");
                }
                int locationMode = parsePathPointInt(routeDetail.getPosition(), 0,
                        "第" + (index + 1) + "个路径点定位模式");
                double workDuration = parsePathPointDouble(routeDetail.getStopTime(), 0.0,
                        "第" + (index + 1) + "个路径点作业时长");
                if (workDuration < 0) {
                    throw new IllegalArgumentException("第" + (index + 1) + "个路径点作业时长不能为负数");
                }

                // 严格对应硬件协议：x、y、yaw、点位ID、速度、运行模式、定位模式、预留、作业时长。
                finalData.add(x);
                finalData.add(y);
                finalData.add(targetYaw);
                finalData.add(station.getId());
                finalData.add(speed);
                finalData.add(runMode);
                finalData.add(locationMode);
                finalData.add(0); // data[7]：硬件协议预留字段，不能放 lanechange。
                finalData.add(workDuration);
            }

            int payloadSize = finalData.size() - PATH_POINT_HEADER_SIZE;
            int actualPointCount = payloadSize / PATH_POINT_FIELD_COUNT;
            if (payloadSize % PATH_POINT_FIELD_COUNT != 0 || actualPointCount != stationIdList.size()) {
                log.error("生成的 path_point 数据长度不符合硬件协议: routeId={}, dataSize={}, pointCount={}",
                        routeId, finalData.size(), actualPointCount);
                return ResultUtils.error(500, "生成的路径点数据不符合硬件协议");
            }

            log.info("生成 path_point 数据: routeId={}, pointCount={}, dataSize={}",
                    routeId, actualPointCount, finalData.size());
            return ResultUtils.success(finalData);
        } catch (IllegalArgumentException e) {
            log.error("路线点协议数据错误: routeId={}, 错误: {}", routeId, e.getMessage());
            return ResultUtils.error(400, "路线点协议数据错误: " + e.getMessage());
        } catch (Exception e) {
            log.error("构建路线点协议数据失败: routeId={}", routeId, e);
            return ResultUtils.error(500, "构建路线点协议数据失败: " + e.getMessage());
        }
    }

    /** 查询路线点详情，并按路线站点顺序取第一条有效记录。 */
    private RouteDetail findPathPointRouteDetail(Long routeId, Long stationId) {
        QueryWrapper<RouteDetail> wrapper = new QueryWrapper<>();
        wrapper.eq("routeId", routeId)
                .eq("stationId", stationId)
                .eq("isDelete", 0)
                .orderByAsc("id");
        List<RouteDetail> details = routeDetailMapper.selectList(wrapper);
        return details.isEmpty() ? new RouteDetail() : details.get(0);
    }

    /** 方向字段按数据库约定保存为度，发送给机器人时转换为弧度。 */
    private double resolvePathPointYaw(RouteDetail routeDetail, Station station, int index) {
        if (StringUtils.isNotBlank(routeDetail.getDirection())) {
            double directionDegrees = parsePathPointDouble(routeDetail.getDirection(), 0.0,
                    "第" + (index + 1) + "个路径点目标方向");
            return Math.toRadians(directionDegrees);
        }

        // 未配置点位方向时，使用站点四元数中的 yaw 作为安全回退值。
        try {
            return extractYaw(station);
        } catch (RuntimeException e) {
            log.warn("第{}个路径点方向为空且站点姿态无法解析，使用0弧度: stationId={}, 错误={}",
                    index + 1, station.getId(), e.getMessage());
            return 0.0;
        }
    }

    private double parsePathPointDouble(String value, double defaultValue, String fieldName) {
        if (StringUtils.isBlank(value)) {
            return defaultValue;
        }
        double parsed = Double.parseDouble(value.trim());
        if (!Double.isFinite(parsed)) {
            throw new IllegalArgumentException(fieldName + "必须是有限数字");
        }
        return parsed;
    }

    private int parsePathPointInt(String value, int defaultValue, String fieldName) {
        if (StringUtils.isBlank(value)) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(fieldName + "必须是整数: " + value);
        }
    }

    private double extractYaw(Station station) {
        Quaternion q = new Quaternion(station.getOrientationW(), station.getOrientationX(), station.getOrientationY(), station.getOrientationZ());
        return Math.atan2(2 * (q.getW() * q.getZ() + q.getX() * q.getY()), 1 - 2 * (q.getY() * q.getY() + q.getZ() * q.getZ()));
    }
    @Data
    private class Quaternion {
        private Double W;
        private Double X;
        private Double Y;
        private Double Z;

        public Quaternion(String orientationW, String orientationX, String orientationY, String orientationZ) {
            this.W = Double.valueOf(orientationW);
            this.X = Double.valueOf(orientationX);
            this.Y = Double.valueOf(orientationY);
            this.Z = Double.valueOf(orientationZ);
        }
    }

    private StationRequest createStationRequestFromMapData(RouteRequest request, int index) {
        StationRequest stationRequest = new StationRequest();
        stationRequest.setLongitude(request.getRouteMapData()[index]);
        stationRequest.setLatitude(request.getRouteMapData()[index + 1]);
        stationRequest.setPositionX("0");
        stationRequest.setPositionY("0");
        stationRequest.setPositionZ("0");
        stationRequest.setOrientationX("0");
        stationRequest.setOrientationY("0");
        stationRequest.setOrientationZ("0");
        stationRequest.setOrientationW("0");
        stationRequest.setStationCode("0");
        return stationRequest;
    }

    private String generateStationName(String prefix) {
        queryMapStationList(); // 更新最新编号
        int number = "S".equals(prefix) ? latestStationNumber + 1 :
                "Auto".equals(prefix) ? latestAutoNumber + 1 :
                        latestEdgeNumber + 1;
        return prefix + number;
    }

    public int queryMapStationList() {
        QueryWrapper<Station> queryWrapper = new QueryWrapper<>();
        List<Station> stationList = stationService.list(queryWrapper);
        queryWrapper.eq("isDelete", 0); // 添加条件，过滤掉 isDelete 为 1 的记录

        // 用来存储提取出的数字
        List<Integer> stationNumbers = new ArrayList<>();

        Pattern pattern = Pattern.compile("^" + prefix + "(\\d+)$"); // 正则表达式匹配格式 "Station" 后跟数字

        // 遍历所有站点，提取符合规则的数字
        for (Station station : stationList) {
            String stationName = station.getStationName();
            Matcher matcher = pattern.matcher(stationName);
            if (matcher.matches()) {
                // 提取数字部分并转换为整数
                int number = Integer.parseInt(matcher.group(1));
                stationNumbers.add(number);
            }
        }
        // 如果有符合规则的站点名，找到最大数字
        if (!stationNumbers.isEmpty()) {
            latestStationNumber = Collections.max(stationNumbers);
        }
        else {
            latestStationNumber = 0;
        }


        pattern = Pattern.compile("^" + Auto + "(\\d+)$"); // 正则表达式匹配格式 "Station" 后跟数字

        // 遍历所有站点，提取符合规则的数字
        for (Station station : stationList) {
            String stationName = station.getStationName();
            Matcher matcher = pattern.matcher(stationName);
            if (matcher.matches()) {
                // 提取数字部分并转换为整数
                int number = Integer.parseInt(matcher.group(1));
                stationNumbers.add(number);
            }
        }
        // 如果有符合规则的站点名，找到最大数字
        if (!stationNumbers.isEmpty()) {
            latestAutoNumber = Collections.max(stationNumbers);
        }
        else {
            latestAutoNumber = 0;
        }

        pattern = Pattern.compile("^" + Edge + "(\\d+)$"); // 正则表达式匹配格式 "Station" 后跟数字

        // 遍历所有站点，提取符合规则的数字
        for (Station station : stationList) {
            String stationName = station.getStationName();
            Matcher matcher = pattern.matcher(stationName);
            if (matcher.matches()) {
                // 提取数字部分并转换为整数
                int number = Integer.parseInt(matcher.group(1));
                stationNumbers.add(number);
            }
        }
        // 如果有符合规则的站点名，找到最大数字
        if (!stationNumbers.isEmpty()) {
            latestEdgeNumber = Collections.max(stationNumbers);
        }
        else {
            latestEdgeNumber = 0;
        }

        // 输出结果
        log.info("Latest Station Number: " + latestStationNumber);
        // 返回原始站点列表
        return 0;
    }




    /*private static final String CHARSET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        // 生成指定长度的随机字符串
    public static String generateRandomString(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            int index = random.nextInt(CHARSET.length());
            sb.append(CHARSET.charAt(index));
        }
        return sb.toString();
    }*/

    @Data
    static class Point {
        private double latitude;
        private double longitude;

        public Point(double latitude,double longitude) {
            this.latitude = latitude;
            this.longitude = longitude;
        }

    }

    private static List<Point> getHorizontalIntersections(List<Point> polygon, double y) {
        List<Point> intersections = new ArrayList<>();

        for (int i = 0; i < polygon.size(); i++) {
            Point p1 = polygon.get(i);
            Point p2 = polygon.get((i + 1) % polygon.size());

            // 情况1：处理水平边
            if (p1.getLongitude() == p2.getLongitude()) {
                if (Math.abs(p1.getLongitude() - y) < 1e-6) { // 浮点精度容差
                    // 添加有序端点
                    Point[] points = p1.getLatitude() < p2.getLatitude()
                            ? new Point[]{p1, p2}
                            : new Point[]{p2, p1};
                    intersections.add(points[0]);
                    intersections.add(points[1]);
                }
            }
            // 情况2：处理非水平边
            else if ((p1.getLongitude() < y && p2.getLongitude() >= y) || (p1.getLongitude() > y && p2.getLongitude() <= y)) {
                double t = (y - p1.getLongitude()) / (p2.getLongitude() - p1.getLongitude());
                double x = p1.getLatitude() + t * (p2.getLatitude() - p1.getLatitude());
                intersections.add(new Point(x, y));
            }
        }

        // 去重处理（基于坐标精度）
        List<Point> unique = new ArrayList<>();
        for (Point p : intersections) {
            boolean exists = unique.stream().anyMatch(
                    up -> Math.abs(up.getLatitude() - p.getLatitude()) < 1e-6 && Math.abs(up.getLongitude() - p.getLongitude()) < 1e-6
            );
            if (!exists) unique.add(p);
        }
        // 按X坐标排序
        unique.sort((a, b) -> Double.compare(a.getLatitude(), b.getLatitude()));
        return unique;
    }

    // 根据航向角(yaw)计算四元数
    private double[] calculateQuaternionFromYaw(double yaw) {
        // 四元数表示绕Z轴的旋转
        double cy = Math.cos(yaw * 0.5);
        double sy = Math.sin(yaw * 0.5);

        return new double[]{
                0,          // x分量（绕X轴旋转为0）
                0,          // y分量（绕Y轴旋转为0）
                sy,         // z分量
                cy          // w分量
        };
    }


    public static List<Point[]> boustrophedonCoverage(List<Point> polygon, double stepSize) {
        // 计算多边形边界
        double minY = Double.POSITIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        for (Point p : polygon) {
            if (p.getLongitude() < minY) minY = p.getLongitude();
            if (p.getLongitude() > maxY) maxY = p.getLongitude();
        }

        List<Point[]> path = new ArrayList<>();

        // 使用整数步数避免浮点精度问题
        int totalSteps = (int) Math.ceil((maxY - minY) / stepSize);
        //Point lastPoint = polygon.get(0);
        Point lastPoint = null;

        for (int step = 0; step <= totalSteps; step++) {
            double currentY = minY + step * stepSize;

            // 处理浮点精度上限
            if (currentY > maxY) currentY = maxY;

            List<Point> intersections = getHorizontalIntersections(polygon, currentY);
            intersections.sort((a, b) -> Double.compare(a.getLatitude(), b.getLatitude()));

            // 特殊处理顶部和底部水平线的奇数交点
            if (intersections.size() % 2 != 0) {
                if (Math.abs(currentY - minY) < 1e-6 || Math.abs(currentY - maxY) < 1e-6) {
                    // 如果是顶部或底部水平线，保留奇数交点
                    Point singlePoint = intersections.get(0);
                    if (lastPoint == null) {
                        lastPoint = singlePoint;
                    }else if (!pointsEqual(lastPoint,singlePoint)) {
                        path.add(new Point[]{lastPoint,singlePoint});
                        lastPoint = singlePoint;
                    }
                    continue;
                } else {
                    System.err.println("Warning: 奇数交点数量 at Y = " + currentY);
                    continue;
                }
            }

            // 生成蛇形路径
            boolean isEvenRow = (step % 2) == 0;
            for (int i = 0; i < intersections.size(); i += 2) {
                Point left = intersections.get(i);
                Point right = intersections.get(i + 1);

                // 保持原始Y坐标值
                Point start = isEvenRow ? left : right;
                Point end = isEvenRow ? right : left;

                if (lastPoint == null) {
                    path.add(new Point[]{start, end});
                    lastPoint = end;
                }else {
                    if (!pointsEqual(start, end) && !pointsEqual(lastPoint, start)) {
                        path.add(new Point[]{lastPoint, start});
                        path.add(new Point[]{start, end});
                        lastPoint = end;
                    } else if (!pointsEqual(start, end)) {
                        path.add(new Point[]{start, end});
                        lastPoint = end;
                    }
                }
            }
        }
        return path;
    }

    public static double normalizeAngleTo180(double angle) {
        angle = angle % 360;  // 先规范化到 0-360 或 -360-0
        if (angle > 180) {
            angle -= 360;  // 如果大于180，转换为负数（-180到0）
        } else if (angle < -180) {
            angle += 360;  // 如果小于-180，转换为正数（0到180）
        }
        return angle;
    }

    private static boolean pointsEqual(Point a, Point b) {
        return Math.abs(a.getLatitude() - b.getLatitude()) < 1e-6 && Math.abs(a.getLongitude() - b.getLongitude()) < 1e-6;
    }

    // 支持多种格式的stationIds解析
    private List<Long> parseStationIds(String stationIdsStr) {
        if (StringUtils.isBlank(stationIdsStr)) {
            return Collections.emptyList();
        }

        try {
            // 尝试解析为JSON数组
            if (stationIdsStr.startsWith("[") && stationIdsStr.endsWith("]")) {
                return JSON.parseArray(stationIdsStr, Long.class);
            }

            // 尝试解析为逗号分隔的字符串
            if (stationIdsStr.contains(",")) {
                return Arrays.stream(stationIdsStr.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .map(Long::valueOf)
                        .collect(Collectors.toList());
            }

            // 尝试解析为单个ID
            return Collections.singletonList(Long.valueOf(stationIdsStr));
        } catch (Exception e) {
            log.warn("解析stationIds失败: {}", stationIdsStr, e);
            return Collections.emptyList();
        }
    }

    /**
     * 处理Direction字段的特殊逻辑（按照saveRoute中的方式）
     */
    private String processDirectionField(RouteDetailRequest request, Route route, List<Long> stationIds) {
        try {
            if (request.getDirection() == null) {
                return "0"; // 默认值
            }

            double directionValue = Double.parseDouble(request.getDirection());
            log.info("处理站点方向值: {}", directionValue);

            // 获取当前站点在路线中的索引位置
            int currentIndex = stationIds.indexOf(request.getStationId());
            if (currentIndex == -1) {
                return request.getDirection(); // 如果找不到索引，使用原始值
            }

            // 预计算所有站点的XY坐标
            List<double[]> xyCoordinates = new ArrayList<>();
            for (Long stationId : stationIds) {
                Station station = stationService.getById(stationId);
                if (station != null) {
                    double x = Double.parseDouble(station.getPositionX());
                    double y = Double.parseDouble(station.getPositionY());
                    xyCoordinates.add(new double[]{x, y});
                } else {
                    // 如果站点不存在，添加默认坐标
                    xyCoordinates.add(new double[]{0, 0});
                }
            }

            // 按照saveRoute中的逻辑处理方向值
            if (directionValue == 360) {
                // 情况1: Direction=360，计算该点到下一点的航向角
                if (currentIndex < stationIds.size() - 1) {
                    double[] currentXY = xyCoordinates.get(currentIndex);
                    double[] nextXY = xyCoordinates.get(currentIndex + 1);
                    double yaw = calculateYaw(currentXY, nextXY);
                    yaw = normalizeAngleTo180(yaw); // 添加角度规整
                    log.info("计算到下一点的航向角: {}", yaw);
                    return String.valueOf(yaw);
                } else {
                    // 如果是最后一个点，保持原方向或设为默认值
                    log.info("最后一个点，使用默认方向: 0");
                    return "0";
                }
            } else if (directionValue == -360) {
                // 情况2: Direction=-360，计算该点与上一点的航向角
                if (currentIndex > 0) {
                    double[] prevXY = xyCoordinates.get(currentIndex - 1);
                    double[] currentXY = xyCoordinates.get(currentIndex);
                    log.info("前一点坐标: [{}, {}], 当前点坐标: [{}, {}]",
                            prevXY[0], prevXY[1], currentXY[0], currentXY[1]);

                    double baseYaw = calculateYaw(prevXY, currentXY);
                    log.info("计算的基础航向角: {}", baseYaw);

                    double adjustedYaw = baseYaw;
                    adjustedYaw = normalizeAngleTo180(adjustedYaw); // 添加角度规整
                    log.info("调整后的航向角: {}", adjustedYaw);

                    return String.valueOf(adjustedYaw);
                } else {
                    log.info("第一个点，直接使用传入值: {}", directionValue);
                    return String.valueOf(directionValue);
                }
            } else if (directionValue <= 180 && directionValue >= -180) {
                // 情况3: Direction在-180到180之间，使用传入值
                log.info("使用传入方向值: {}", directionValue);
                return String.valueOf(directionValue);
            } else {
                // 其他情况使用默认值
                log.info("方向值超出范围，使用默认值: 0");
                return "0";
            }
        } catch (Exception e) {
            log.error("处理方向字段失败，使用默认值", e);
            return "0";
        }
    }

    // 请求参数类
    @Data
    static class RouteUpdateRequest {
        private Long routeId;
        private String routeName;
        private String mapName;
        private Double speed;
        private List<PointData> points;
    }

    @Data
    static class PointData {
        private Double x;
        private Double y;
        private StationData stationData;
    }

    @Data
    static class StationData {
        private Long id;
        private String positionX;
        private String positionY;
        private String speed;
        private String direction;
        private String area;
        private String action;
        private String stopTime;
    }
    // 计算两点之间的航向角（弧度转角度）
    private double calculateYaw(double[] from, double[] to) {
        double dx = to[0] - from[0];
        double dy = to[1] - from[1];
        double yawRad = Math.atan2(dy, dx);
        double yawDeg = Math.toDegrees(yawRad);
        // 规范化到0-360度
        return (yawDeg + 360) % 360;
    }
}
