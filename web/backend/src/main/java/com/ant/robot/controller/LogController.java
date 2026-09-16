package com.ant.robot.controller;

import com.ant.robot.common.response.BaseResponse;
import com.ant.robot.common.response.ResultUtils;
import com.ant.robot.model.domain.Log;
import com.ant.robot.service.LogService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * @author ChenWeihan
 * @description 日志
 */
@RestController
@RequestMapping("log")
@Slf4j
public class LogController {

    @Resource

    private LogService logService;


    /**
     * 分页查询日志
     */
    @GetMapping("query")
    public BaseResponse<Page<Log>> queryRoute(long pageSize, long pageNum) {
        QueryWrapper<Log> queryWrapper = new QueryWrapper<>();
        Page<Log> logPage = logService.page(new Page<>(pageNum, pageSize), queryWrapper);
        return ResultUtils.success(logPage);
    }
}
