package com.ant.robot.controller;

import com.ant.robot.common.response.BaseResponse;
import com.ant.robot.common.response.ResultUtils;
import com.ant.robot.mapper.SystemConfigMapper;
import com.ant.robot.model.domain.SystemConfig;
import com.ant.robot.model.request.SystemConfigRequest;
import com.ant.robot.service.SystemConfigService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * @author ChenWeihan
 * @description 系统配置【站点初始化】
 *
 */
@RestController
@RequestMapping("system")
@Slf4j
public class SystemConfigController {

    @Resource
    private SystemConfigService systemConfigService;


    @Resource
    private SystemConfigMapper systemConfigMapper;

    /**
     * 获取系统配置
     */
    @GetMapping
    public BaseResponse<List<SystemConfig>> getSystemConfig() {
        QueryWrapper<SystemConfig> systemConfigQueryWrapper = new QueryWrapper<>();
        List<SystemConfig> systemConfigList = systemConfigService.list(systemConfigQueryWrapper);
        return ResultUtils.success(systemConfigList);
    }

    /**
     * 修改指定配置
     */
    @PostMapping("update")
    public BaseResponse<Boolean> updateStation(@RequestBody SystemConfigRequest systemConfigRequest) {

        QueryWrapper<SystemConfig> systemConfigQueryWrapper = new QueryWrapper<>();
        systemConfigQueryWrapper.eq("configKey", systemConfigRequest.getConfigKey());

        SystemConfig systemConfig = new SystemConfig();
        BeanUtils.copyProperties(systemConfigRequest, systemConfig);

        boolean update = systemConfigMapper.update(systemConfig, systemConfigQueryWrapper) != 0;
        return ResultUtils.success(update);
    }


}
