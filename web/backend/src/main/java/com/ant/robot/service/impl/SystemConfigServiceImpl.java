package com.ant.robot.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.ant.robot.model.domain.SystemConfig;
import com.ant.robot.service.SystemConfigService;
import com.ant.robot.mapper.SystemConfigMapper;
import org.springframework.stereotype.Service;

/**
* @author 10345
* @description 针对表【t_system_config(系统配置)】的数据库操作Service实现
* @createDate 2024-04-24 09:06:30
*/
@Service
public class SystemConfigServiceImpl extends ServiceImpl<SystemConfigMapper, SystemConfig>
    implements SystemConfigService{

}




