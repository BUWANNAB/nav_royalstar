package com.ant.robot.model.vo;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 数据管理页分页结果。
 *
 * @author ChenWeihan
 */
@Data
public class DataAdminPageVo {

    /**
     * 满足条件的总行数（不含分页）
     */
    private long total;

    /**
     * 当前页码，从 1 开始
     */
    private long pageNum;

    /**
     * 每页行数
     */
    private long pageSize;

    /**
     * 当前页数据，键为列名
     */
    private List<Map<String, Object>> rows;
}
