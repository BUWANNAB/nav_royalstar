package com.ant.robot.model.vo;

import lombok.Data;

import java.util.List;

/**
 * 数据管理页的列元数据。前端据此渲染表头、表单控件与校验。
 *
 * @author ChenWeihan
 */
@Data
public class DataAdminColumnVo {

    /**
     * 真实数据库列名。前端只用它做键，服务端永远以注册表为准，不接受前端指定列。
     */
    private String name;

    /**
     * 显示名称
     */
    private String label;

    /**
     * 控件类型：string | number | datetime | boolean | select | json
     */
    private String type;

    /**
     * 是否可写。false 表示只读（id / createTime / updateTime / isDelete / 派生列）
     */
    private boolean editable;

    /**
     * 是否必填
     */
    private boolean required;

    /**
     * 是否支持排序
     */
    private boolean sortable;

    /**
     * 是否支持作为筛选条件
     */
    private boolean filterable;

    /**
     * select 类型的可选值
     */
    private List<String> options;

    /**
     * 字段提示（悬浮/表头说明）
     */
    private String hint;

    /**
     * 是否敏感字段（如密码），列表展示需脱敏
     */
    private boolean masked;
}
