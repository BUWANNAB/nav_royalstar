package com.ant.robot.model.request;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据管理页写入请求（新增 / 修改共用）。
 *
 * <p>values 采用「部分更新」语义：只有出现在 values 里的列才会被写入，
 * 未出现的列保持原值。这样即使元数据未暴露某个列（如 t_route_detail.direction_option），
 * 也不会被误覆盖。</p>
 *
 * @author ChenWeihan
 */
@Data
public class DataAdminWriteRequest {

    /**
     * 主表行数据：列名 -> 值
     */
    private Map<String, Object> values = new LinkedHashMap<>();

    /**
     * 复合实体的子表行数据（仅路线使用）。传入即表示「整体替换该路线的明细集合」。
     */
    private List<Map<String, Object>> details;

    /**
     * 是否按业务规则重算明细的 direction（360=到下一点，-360=到上一点）。
     * 默认 false，即 direction 按原值保存。
     */
    private Boolean recomputeDirection = false;
}
