package com.ant.robot.controller;

import com.ant.robot.common.aop.LogAnnotation;
import com.ant.robot.common.response.BaseResponse;
import com.ant.robot.common.response.ResultUtils;
import com.ant.robot.mapper.ParamMapper;
import com.ant.robot.model.domain.Param;
import com.ant.robot.model.request.ParamRequest;
import com.ant.robot.service.ParamService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("param")
@Slf4j
public class ParamController {

    @Resource
    private ParamService paramService;


    @Resource
    private ParamMapper paramSMapper;

    /**
     * 查询所有数据
     */
    @GetMapping("queryalldata")
    public BaseResponse<List<Param>> queryParamList() {
        QueryWrapper<Param> queryWrapper = new QueryWrapper<>();
        List<Param> paramList = paramService.list(queryWrapper);
        return ResultUtils.success(paramList);
    }

    /**
     * 修改参数
     */
    @PostMapping("update")
    @LogAnnotation(title = "机器参数表", content = "更新参数")
    public BaseResponse<Boolean> updateParam(@RequestBody ParamRequest paramRequest) {
        Param param = new Param();
        BeanUtils.copyProperties(paramRequest, param);
        boolean update = paramService.updateById(param);
        return ResultUtils.success(update);
    }
}
