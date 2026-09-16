package com.ant.robot.service.impl;

import com.ant.robot.mapper.SensorMapper;
import com.ant.robot.service.SensorService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.ant.robot.model.domain.Sensor;
import org.springframework.stereotype.Service;

/**
 * @author lzp
 * @description 针对表【t_sensor(传感器数据)】的数据库操作Service实现
 * @createDate 2024-04-23 11:52:21
 */
@Service
public class SensorServiceImpl extends ServiceImpl<SensorMapper, Sensor>
        implements SensorService {
}
