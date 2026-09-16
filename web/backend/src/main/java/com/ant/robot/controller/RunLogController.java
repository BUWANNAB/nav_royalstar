package com.ant.robot.controller;

import com.ant.robot.common.aop.LogAnnotation;
import com.ant.robot.common.response.BaseResponse;
import com.ant.robot.common.response.ResultUtils;
import com.ant.robot.mapper.RunLogMapper;
import com.ant.robot.model.domain.RunLog;
import com.ant.robot.model.request.RunLogRequest;
import com.ant.robot.service.RunLogService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * @author ChenWeihan
 * @description 站点
 */
@RestController
@RequestMapping("carLog")
@Slf4j
public class RunLogController {
    @Resource
    private RunLogService runLogService;
    @Resource
    private RunLogMapper runLogMapper;

    /**
     * 添加运行日志
     */
    @PostMapping("add")
    @LogAnnotation(title = "站点模块", content = "新增站点")
    public BaseResponse<Object> addRunLog(@RequestBody RunLogRequest runLogRequest) {

        RunLog runLog = new RunLog();
        BeanUtils.copyProperties(runLogRequest, runLog);
        boolean save = runLogService.save(runLog);
        return ResultUtils.success(save);
    }

    /**
     * 查询运行日志
     */
    @GetMapping("queryall")
    public BaseResponse<List<RunLog>> queryRunLogList() {
        QueryWrapper<RunLog> queryWrapper = new QueryWrapper<>();
        List<RunLog> runLogList = runLogService.list(queryWrapper);
        return ResultUtils.success(runLogList);
    }
}
