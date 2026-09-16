package com.ant.robot.service.impl;

import com.ant.robot.service.LogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.ant.robot.model.domain.Log;
import com.ant.robot.mapper.LogMapper;
import org.springframework.stereotype.Service;

/**
* @author 10345
* @description 针对表【t_log(操作日志记录)】的数据库操作Service实现
* @createDate 2024-04-04 16:10:43
*/
@Service
public class LogServiceImpl extends ServiceImpl<LogMapper, Log>
    implements LogService {

}




