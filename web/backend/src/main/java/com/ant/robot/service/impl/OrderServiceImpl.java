package com.ant.robot.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.ant.robot.model.domain.Order;
import com.ant.robot.service.OrderService;
import com.ant.robot.mapper.OrderMapper;
import org.springframework.stereotype.Service;

/**
* @author 10345
* @description 针对表【t_order(订单)】的数据库操作Service实现
* @createDate 2024-04-04 16:58:53
*/
@Service
public class OrderServiceImpl extends ServiceImpl<OrderMapper, Order>
    implements OrderService{

}




