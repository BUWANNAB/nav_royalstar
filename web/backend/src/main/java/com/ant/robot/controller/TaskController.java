package com.ant.robot.controller;

import com.ant.robot.common.aop.LogAnnotation;
import com.ant.robot.common.enums.ErrorCode;
import com.ant.robot.common.response.BaseResponse;
import com.ant.robot.common.response.ResultUtils;
import com.ant.robot.mapper.TaskMapper;
import com.ant.robot.model.domain.Route;
import com.ant.robot.model.domain.Task;
import com.ant.robot.model.request.TaskRequest;
import com.ant.robot.service.TaskService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("task")
@Slf4j
public class TaskController {

    @Resource
    private TaskService taskService;

    @Resource
    private TaskMapper taskMapper;

    @Autowired
    private RouteController routeController;

    /**
     * 添加站点
     */
    @PostMapping("add")
    @LogAnnotation(title = "站点模块", content = "新增站点")
    public BaseResponse<Object> addTask(@RequestBody TaskRequest taskRequest) {
        try {
            Task task = new Task();
            BeanUtils.copyProperties(taskRequest, task);
            // 根据routeId获取routeName
            if (taskRequest.getRouteId() != null && !taskRequest.getRouteId().isEmpty()) {
                Long routeId = Long.parseLong(taskRequest.getRouteId());
                BaseResponse<Route> routeResponse = routeController.queryRoute(routeId);
                if (routeResponse != null && routeResponse.getData() != null) {
                    String routeName = routeResponse.getData().getRouteName();
                    task.setRouteName(routeName);
                }
            }
            boolean save = taskService.save(task);
            return ResultUtils.success(save);
        } catch (Exception e) {
            log.error("添加任务失败", e);
            return ResultUtils.error(ErrorCode.valueOf("添加任务失败: " + e.getMessage()));
        }
    }

    /**
     * 查询所有数据
     */
    @GetMapping("queryall")
    public BaseResponse<List<Task>> queryParamList() {
        QueryWrapper<Task> queryWrapper = new QueryWrapper<>();
        List<Task> taskList = taskService.list(queryWrapper);
        return ResultUtils.success(taskList);
    }

    /**
     * 修改参数
     */
    @PostMapping("update")
    @LogAnnotation(title = "机器参数表", content = "更新参数")
    public BaseResponse<Boolean> updateParam(@RequestBody TaskRequest taskRequest) {
        Task task = new Task();
        BeanUtils.copyProperties(taskRequest, task);
        boolean update = taskService.updateById(task);
        return ResultUtils.success(update);
    }

    /**
     * 删除任务
     */
    @DeleteMapping("delete/{id}")
    public BaseResponse<Boolean> deleteTask(@PathVariable("id") Long id) {
        boolean delete = taskService.removeById(id);
        return ResultUtils.success(delete);
    }
}
