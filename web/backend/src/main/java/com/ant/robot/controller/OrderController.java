package com.ant.robot.controller;

import com.ant.robot.common.aop.LogAnnotation;
import com.ant.robot.common.constants.SystemConstant;
import com.ant.robot.common.response.BaseResponse;
import com.ant.robot.common.response.ResultUtils;
import com.ant.robot.mapper.OrderMapper;
import com.ant.robot.model.domain.Order;
import com.ant.robot.model.request.OrderRequest;
import com.ant.robot.service.OrderService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * @author ChenWeihan
 * @description 订单
 */
@RestController
@RequestMapping("order")
@Slf4j
@CrossOrigin(origins = "*")
public class OrderController {

    @Resource
    private OrderService orderService;

    @Resource
    private OrderMapper orderMapper;

    /**
     * 日期格式化
     */
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("MM-dd");

    /**
     * 添加订单
     * TODO 判断是否 name 重复
     */
    @PostMapping("add")
    @LogAnnotation(title = "订单模块", content = "新增订单")
    public BaseResponse<Object> addStation(@RequestBody OrderRequest orderRequest) {
        Order order = new Order();
        BeanUtils.copyProperties(orderRequest, order);
        boolean save = orderService.save(order);
        return ResultUtils.success(save);
    }

    /**
     * 分页查询订单
     */
    @GetMapping("query")
    public BaseResponse<Page<Order>> queryOrder(long pageSize, long pageNum) {
        QueryWrapper<Order> queryWrapper = new QueryWrapper<>();
        Page<Order> orderPage = orderService.page(new Page<>(pageNum, pageSize), queryWrapper);
        return ResultUtils.success(orderPage);
    }

    /**
     * 查询所有路线
     */
    @GetMapping("queryall")
    public BaseResponse<List<Order>> queryOrderList() {
        QueryWrapper<Order> queryWrapper = new QueryWrapper<>();
        List<Order> routeList = orderService.list(queryWrapper);
        return ResultUtils.success(routeList);
    }


    /**
     * 最近订单(7天)
     * {
     *   "2024-05-20": [1, 2],
     *   "2024-05-18": [3, 4],
     *   // ...
     * }
     */
    @Deprecated
    public BaseResponse<Map<String, List<Long>>> recentOrder() {
        List<Order> orderList = orderMapper.findOrdersCreatedWithinLastSevenDays();

        // 将订单按照日期分组并收集订单ID
        Map<String, List<Long>> groupedOrders = orderList.stream()
                .collect(Collectors.groupingBy(
                        order -> order.getCreateTime().toInstant()
                                .atZone(ZoneId.systemDefault())
                                .format(DATE_FORMATTER),
                        Collectors.mapping(Order::getId, Collectors.toList())
                ));

        // 将Map转换为List，以便按日期键排序
        List<Map.Entry<String, List<Long>>> sortedEntries = new ArrayList<>(groupedOrders.entrySet());
        // 按键（日期）升序排序
        sortedEntries.sort(Map.Entry.comparingByKey());

        // 再次收集为LinkedHashMap，以保持排序顺序
        Map<String, List<Long>> sortedGroupedOrders = new LinkedHashMap<>();
        for (Map.Entry<String, List<Long>> entry : sortedEntries) {
            sortedGroupedOrders.put(entry.getKey(), entry.getValue());
        }

        return ResultUtils.success(sortedGroupedOrders);
    }

    /**
     * 最近订单(7天) 如果某天日期没有订单，也返回0
     * {
     *   "2024-05-20": [1, 2],
     *   "2024-05-18": [3, 4],
     *   // ...
     * }
     */
    @GetMapping("recent")
    public BaseResponse<Map<String, Integer>> recentOrderCountForEachDayOfLastWeek() {
        // 获取过去6天的日期，包括今天
        List<LocalDate> pastSixDays = LocalDate.now().minusDays(6).datesUntil(LocalDate.now().plusDays(1)).toList();

        // 查询每个日期的订单数量
        Map<String, Integer> orderCounts = new HashMap<>(7);
        for (LocalDate date : pastSixDays) {
            Date dateInSqlFormat = Date.from(date.atStartOfDay(ZoneId.of(SystemConstant.ZONE)).toInstant());
            int count = orderMapper.findOrdersByDate(dateInSqlFormat);
            orderCounts.put(date.format(DATE_FORMATTER), count);
        }

        // 确保Map按照日期升序排列
        Map<String, Integer> sortedOrderCounts = new TreeMap<>(orderCounts);


        return ResultUtils.success(sortedOrderCounts);
    }
}