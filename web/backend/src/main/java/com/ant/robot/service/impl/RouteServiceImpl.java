package com.ant.robot.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.ant.robot.model.domain.Route;
import com.ant.robot.service.RouteService;
import com.ant.robot.mapper.RouteMapper;
import org.springframework.stereotype.Service;

/**
* @author 10345
* @description 针对表【t_route(路线)】的数据库操作Service实现
* @createDate 2024-04-04 16:58:45
*/
@Service
public class RouteServiceImpl extends ServiceImpl<RouteMapper, Route>
    implements RouteService{
}




