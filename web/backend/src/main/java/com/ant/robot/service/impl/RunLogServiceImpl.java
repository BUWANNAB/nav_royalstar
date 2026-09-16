package com.ant.robot.service.impl;

import com.ant.robot.mapper.RunLogMapper;
import com.ant.robot.model.domain.RunLog;
import com.ant.robot.service.RunLogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

/**
* @author 10345
* @description 针对表【t_log(操作日志记录)】的数据库操作Service实现
* @createDate 2024-04-04 16:10:43
*/
@Service
public class RunLogServiceImpl extends ServiceImpl<RunLogMapper, RunLog>
    implements RunLogService {

}




