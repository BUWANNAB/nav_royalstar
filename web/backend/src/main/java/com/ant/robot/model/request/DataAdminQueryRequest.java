package com.ant.robot.model.request;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 数据管理页查询请求。
 *
 * @author ChenWeihan
 */
@Data
public class DataAdminQueryRequest {

    /**
     * 页码，从 1 开始
     */
    private Long pageNum = 1L;

    /**
     * 每页行数，服务端会做上限钳制
     */
    private Long pageSize = 20L;

    /**
     * 是否包含已逻辑删除的数据。默认 false，与原 queryall 行为一致。
     */
    private Boolean includeDeleted = false;

    /**
     * 排序字段（须在元数据中且 sortable=true）
     */
    private String sortBy;

    /**
     * 排序方向：asc / desc
     */
    private String sortOrder;

    /**
     * 筛选条件：列名 -> 关键字。字符串列走 LIKE，其余列走等值匹配。
     */
    private Map<String, String> filters = new LinkedHashMap<>();
}
