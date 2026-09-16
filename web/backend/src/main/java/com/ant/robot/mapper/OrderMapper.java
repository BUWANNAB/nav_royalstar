package com.ant.robot.mapper;

import com.ant.robot.model.domain.Order;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Select;

import java.util.Date;
import java.util.List;

/**
* @author 10345
* @description 针对表【t_order(订单)】的数据库操作Mapper
* @createDate 2024-04-04 16:58:53
* @Entity com.ant.robot.model.domain.Order
*/
public interface OrderMapper extends BaseMapper<Order> {

    /**
     * 查询最近7天订单
     */
    @Select("SELECT * FROM t_order WHERE createTime >= DATE_SUB(CURDATE(), INTERVAL 6 DAY)")
    List<Order> findOrdersCreatedWithinLastSevenDays();


    /**
     * 查询特定日期的订单
     */
    @Select("SELECT COUNT(*) FROM t_order WHERE DATE(createTime) = #{date}")
    int findOrdersByDate(Date date);
}




