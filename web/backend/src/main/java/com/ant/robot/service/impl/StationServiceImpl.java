package com.ant.robot.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.ant.robot.model.domain.Station;
import com.ant.robot.service.StationService;
import com.ant.robot.mapper.StationMapper;
import org.springframework.stereotype.Service;

/**
* @author 10345
* @description 针对表【t_station(站点)】的数据库操作Service实现
* @createDate 2024-04-23 11:52:21
*/
@Service
public class StationServiceImpl extends ServiceImpl<StationMapper, Station>
    implements StationService{

}




