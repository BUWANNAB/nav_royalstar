package com.ant.robot.service.impl;

import com.ant.robot.mapper.TaskMapper;
import com.ant.robot.model.domain.Task;
import com.ant.robot.service.TaskService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

/**
 * @author lzp
 * @description 针对表【t_task(传感器数据)】的数据库操作Service实现
 * @createDate 2024-04-23 11:52:21
 */
@Service
public class TaskServiceImpl extends ServiceImpl<TaskMapper, Task>
        implements TaskService {
}
