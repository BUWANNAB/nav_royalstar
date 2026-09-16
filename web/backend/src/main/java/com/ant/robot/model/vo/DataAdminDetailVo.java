package com.ant.robot.model.vo;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 数据管理页详情：主行 + 复合实体的子行。
 *
 * @author ChenWeihan
 */
@Data
public class DataAdminDetailVo {

    /**
     * 主表行数据
     */
    private Map<String, Object> row;

    /**
     * 子表行数据（复合实体才有；顺序即明细顺序）
     */
    private List<Map<String, Object>> details;
}
