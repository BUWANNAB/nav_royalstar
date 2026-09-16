package com.ant.robot.controller;

import com.ant.robot.common.response.BaseResponse;
import com.ant.robot.common.response.ResultUtils;
import com.ant.robot.mapper.WaterdepthMapper;
import com.ant.robot.model.domain.Waterdepth;
import com.ant.robot.service.WaterdepthService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * @author ChenWeihan
 * @description 
 */
@RestController
@RequestMapping("waterdepth")
@Slf4j
public class WaterdepthController {

    @Resource
    private WaterdepthService waterdepthService;
    @Resource
    private WaterdepthMapper waterdepthMapper;

    /**
      删除
     */
    @DeleteMapping("delete/{id}")
    public BaseResponse<Boolean> deletewaterdepth(@PathVariable("id") Long id) {
        boolean delete = waterdepthService.removeById(id);
        return ResultUtils.success(delete);
    }

    /**
     * 分页查询
     */
    @GetMapping("query")
    public BaseResponse<Page<Waterdepth>> querywaterdepth(long pageSize, long pageNum) {
        QueryWrapper<Waterdepth> queryWrapper = new QueryWrapper<>();
        Page<Waterdepth> waterdepthPage = waterdepthService.page(new Page<>(pageNum, pageSize), queryWrapper);
        return ResultUtils.success(waterdepthPage);
    }

    /**
     * 查询所有
     */
    @GetMapping("queryall")
    public BaseResponse<List<Waterdepth>> querywaterdepthList() {
        QueryWrapper<Waterdepth> queryWrapper = new QueryWrapper<>();
        List<Waterdepth> waterdepthList = waterdepthService.list(queryWrapper);
        // 打印日志
        return ResultUtils.success(waterdepthList);
    }

}
