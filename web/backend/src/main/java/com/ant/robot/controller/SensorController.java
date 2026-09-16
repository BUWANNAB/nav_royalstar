package com.ant.robot.controller;

import com.ant.robot.common.enums.ErrorCode;
import com.ant.robot.common.response.BaseResponse;
import com.ant.robot.common.response.ResultUtils;
import com.ant.robot.mapper.SensorMapper;
import com.ant.robot.model.domain.Sensor;
import com.ant.robot.service.SensorService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Date;
import java.util.List;

/**
 * @author lzp
 * @description 传感器数据
 */
@RestController
@RequestMapping("sensor")
@Slf4j
public class SensorController {

    @Resource
    private SensorService sensorService;


    @Resource
    private SensorMapper sensorMapper;

    /**
     * 根据createTime查询数据
     */
    @GetMapping("query/{createTime}")
    public BaseResponse<Sensor> querySensorByTIME(@PathVariable("createTime") Date createTime) {
        QueryWrapper<Sensor> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("createTime", createTime);
        Sensor sensor = sensorMapper.selectOne(queryWrapper);
        if (sensor == null) {
            return ResultUtils.error(ErrorCode.NULL_ERROR, "数据不存在");
        }
        return ResultUtils.success(sensor);
    }

    /**
     * 根据stationname查询数据
     */
    @GetMapping("query/{stationname}")
    public BaseResponse<Sensor> querySensorByStation(@PathVariable("stationname") Date stationname) {
        QueryWrapper<Sensor> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("stationname", stationname);
        Sensor sensor = sensorMapper.selectOne(queryWrapper);
        if (sensor == null) {
            return ResultUtils.error(ErrorCode.NULL_ERROR, "数据不存在");
        }
        return ResultUtils.success(sensor);
    }

    /**
     * 查询所有数据
     */
    @GetMapping("queryalldata")
    public BaseResponse<List<Sensor>> querySensorList() {
        QueryWrapper<Sensor> queryWrapper = new QueryWrapper<>();
        List<Sensor> sensorList = sensorService.list(queryWrapper);
        return ResultUtils.success(sensorList);
    }
}
