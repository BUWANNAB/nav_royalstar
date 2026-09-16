package com.ant.robot.controller;

import com.ant.robot.common.aop.LogAnnotation;
import com.ant.robot.common.enums.ErrorCode;
import com.ant.robot.common.response.BaseResponse;
import com.ant.robot.common.response.ResultUtils;
import com.ant.robot.mapper.ParamMapper;
import com.ant.robot.mapper.RouteMapper;
import com.ant.robot.mapper.StationMapper;
import com.ant.robot.model.domain.Param;
import com.ant.robot.model.domain.Route;
import com.ant.robot.model.domain.Station;
import com.ant.robot.model.request.StationRequest;
import com.ant.robot.service.StationService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * @author ChenWeihan
 * @description 站点
 */
@RestController
@RequestMapping("station")
@Slf4j
public class StationController {

    public int latestStationNumber = 0;

    @Resource
    private StationService stationService;
    @Resource
    private StationMapper stationMapper;
    @Resource
    private RouteMapper routeMapper;
    @Resource
    private ParamMapper paramMapper;
    @Resource
    private Llh2xyzController llh2xyzController;


    /**
     * 添加站点
     */
    @PostMapping("add")
    @LogAnnotation(title = "站点模块", content = "新增站点")
    public BaseResponse<Object> addStation(@RequestBody StationRequest stationRequest) {

        if (checkStationRepeat(stationRequest) != null) {
            return ResultUtils.error(ErrorCode.PARAMS_ERROR, "站点名称重复");
        }

        QueryWrapper<Param> paramQueryWrapper = new QueryWrapper<>();
        Param param = paramMapper.selectOne(paramQueryWrapper);
        double localLat = param != null ? Double.parseDouble(param.getLocal_origin_latitude()) : 0;
        double localLon = param != null ? Double.parseDouble(param.getLocal_origin_longitude()) : 0;

        Station station = new Station();
        BeanUtils.copyProperties(stationRequest, station);
        boolean xyEmpty = StringUtils.isBlank(station.getPositionX()) ||
                StringUtils.isBlank(station.getPositionY()) ||
                "0".equals(station.getPositionX()) ||
                "0".equals(station.getPositionY());

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
            double longitude = Double.parseDouble(station.getLongitude());
            double latitude = Double.parseDouble(station.getLatitude());

            double[] xy = llh2xyzController.convertToLocalCoordinates(
                    localLat, localLon, latitude, longitude);

            station.setPositionX(String.valueOf(xy[0]));
            station.setPositionY(String.valueOf(xy[1]));
            station.setPositionZ("0");
        }
        // 如果经纬度为空或0，根据XY坐标计算经纬度
        else if (latLonEmpty) {
            double x = Double.parseDouble(station.getPositionX());
            double y = Double.parseDouble(station.getPositionY());

            double[] latLon = llh2xyzController.convertFromLocalCoordinates(
                    localLat, localLon, x, y, 0);

            station.setLatitude(String.valueOf(latLon[0]));
            station.setLongitude(String.valueOf(latLon[1]));
        }
        boolean save = stationService.save(station);
        return ResultUtils.success(save);
    }

    /**
     * 添加站点--经纬度
     */
    @PostMapping("enadd")
    @LogAnnotation(title = "站点模块", content = "新增站点")
    public BaseResponse<Object> addMapStation(@RequestBody StationRequest stationRequest) {
        log.info("stationRequest {}",stationRequest);
        // 检查站点是否重复
        if (checkStationRepeat(stationRequest) != null) {
            return ResultUtils.error(ErrorCode.PARAMS_ERROR, "站点名称重复");
        }

        // 创建 Station 对象并复制属性
        Station station = new Station();
        BeanUtils.copyProperties(stationRequest, station);

        // 保存站点
        boolean save = stationService.save(station);

        if (!save) {
            return ResultUtils.error(ErrorCode.PARAMS_ERROR, "保存站点失败");
        }

        // 返回成功结果，并返回保存后的站点ID
        return ResultUtils.success(station.getId()); // 返回保存后的站点ID
    }

    /**
     * 删除站点
     */
    @DeleteMapping("delete/{id}")
    @LogAnnotation(title = "站点模块", content = "删除站点")
    public BaseResponse<Boolean> deleteStation(@PathVariable("id") Long id) {
        // 将站点 ID 转换为字符串，准备进行模糊查询
        String stationIdStr = String.valueOf(id);  // 将 Long 类型的 ID 转换为字符串
        // 查找所有包含该站点的 Route 记录
        QueryWrapper<Route> routeQueryWrapper = new QueryWrapper<>();
        routeQueryWrapper.like("stationIds", stationIdStr);  // 精确查找包含该站点 ID 的路线
        // 通过注入的 routeMapper 查询包含该站点的所有路线
        List<Route> routes = routeMapper.selectList(routeQueryWrapper);
        // 4. 删除包含该站点的所有 Route 记录
        for (Route route : routes) {
            routeMapper.deleteById(route.getId());  // 删除该路线
        }
        // 5. 删除站点
        boolean deleteStation = stationService.removeById(id);
        return ResultUtils.success(deleteStation);
    }

    /**
     * 删除所有站点及其关联的路径
     */
    @DeleteMapping("delete/all")
    @LogAnnotation(title = "站点模块", content = "彻底删除所有站点及路径")
    public BaseResponse<Boolean> deleteAllStationsAndRoutes() {
        // 1. 查找所有包含站点的 Route 记录
        QueryWrapper<Route> routeQueryWrapper = new QueryWrapper<>();
        List<Route> routes = routeMapper.selectList(routeQueryWrapper);

        // 2. 删除所有的 Route 记录
        for (Route route : routes) {
            routeMapper.deleteById(route.getId());  // 删除所有路径记录
        }

        // 3. 删除所有站点
        boolean deleteStations = stationService.remove(null);  // 删除所有站点

        // 4. 返回结果
        if (deleteStations) {
            return ResultUtils.success(true);  // 删除成功
        } else {
            return ResultUtils.error(ErrorCode.NULL_ERROR, "删除站点失败");  // 删除失败
        }
    }


    @DeleteMapping("deletenew/{stationCode}")
    @LogAnnotation(title = "站点模块", content = "删除站点")
    public BaseResponse<Boolean> deleteStationsByCode(@PathVariable("stationCode") String stationCode) {
        // 1. 查找所有匹配该 stationCode 的站点
        QueryWrapper<Station> stationQueryWrapper = new QueryWrapper<>();
        stationQueryWrapper.eq("stationCode", stationCode);
        List<Station> stations = stationService.list(stationQueryWrapper);

        // 如果没有找到匹配的站点，直接返回成功
        if (stations.isEmpty()) {
            return ResultUtils.success(true);
        }

        // 2. 收集所有要删除的站点ID
        List<Long> stationIds = stations.stream()
                .map(Station::getId)
                .collect(Collectors.toList());

        // 3. 查找所有包含这些站点的 Route 记录
        QueryWrapper<Route> routeQueryWrapper = new QueryWrapper<>();
        for (Long stationId : stationIds) {
            routeQueryWrapper.or().like("stationIds", String.valueOf(stationId));
        }
        List<Route> routes = routeMapper.selectList(routeQueryWrapper);

        // 4. 删除包含这些站点的所有 Route 记录
        for (Route route : routes) {
            routeMapper.deleteById(route.getId());
        }

        // 5. 删除所有匹配的站点
        boolean deleteResult = stationService.remove(stationQueryWrapper);

        return ResultUtils.success(deleteResult);
    }

    /**
     * 修改站点
     */
    @PostMapping("update")
    @LogAnnotation(title = "站点模块", content = "更新站点")
    public BaseResponse<Boolean> updateStation(@RequestBody StationRequest stationRequest) {

        Station station = new Station();
        BeanUtils.copyProperties(stationRequest, station);
        boolean update = stationService.updateById(station);
        return ResultUtils.success(update);
    }

    /**
     * 查询所有站点
     */
    @GetMapping("queryall")
    public BaseResponse<List<Station>> queryStationList() {
        QueryWrapper<Station> queryWrapper = new QueryWrapper<>();
        List<Station> stationList = stationService.list(queryWrapper);
        return ResultUtils.success(stationList);
    }

    /**
     * 根据id查询站点
     */
    @GetMapping("query/{id}")
    public BaseResponse<Station> queryStationById(@PathVariable("id") Long id) {
        QueryWrapper<Station> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("id", id);
        Station station = stationMapper.selectOne(queryWrapper);
        if (station == null) {
            return ResultUtils.error(ErrorCode.NULL_ERROR, "站点不存在");
        }
        return ResultUtils.success(station);
    }
    /**
     * 根据站点保存方式查询站点
     * @param stationCode 站点编码
     * @param mapName 地图名称（可选，用于筛选）
     */
    @GetMapping("querynew/{stationCode}")
    public BaseResponse<List<Object>> queryStationBystationCode(
            @PathVariable("stationCode") Long stationCode,
            @RequestParam(required = false) String mapName) {
        QueryWrapper<Station> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("stationCode", stationCode);
        if (mapName != null && !mapName.trim().isEmpty()) {
            queryWrapper.eq("map_name", mapName);
        }
        List<Station> station = stationMapper.selectList(queryWrapper);
        if (station == null) {
            return ResultUtils.error(ErrorCode.NULL_ERROR, "站点不存在");
        }
        return ResultUtils.success((Collections.singletonList(station)));
    }

    private Station checkStationRepeat(StationRequest stationRequest) {
        QueryWrapper<Station> wrapper = new QueryWrapper<Station>().eq("stationName", stationRequest.getStationName());
        // 同地图下才判断重复
        if (stationRequest.getMapName() != null && !stationRequest.getMapName().trim().isEmpty()) {
            wrapper.eq("map_name", stationRequest.getMapName());
        } else {
            wrapper.isNull("map_name");
        }
        return stationMapper.selectOne(wrapper);
    }

}
