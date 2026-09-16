package com.ant.robot.model.vo;

import lombok.Data;

import java.util.List;

/**
 * 数据管理页的实体元数据。
 *
 * @author ChenWeihan
 */
@Data
public class DataAdminEntityVo {

    /**
     * 实体键（前端使用），如 station / route / routeDetail
     */
    private String key;

    /**
     * 显示名称
     */
    private String label;

    /**
     * 真实表名。仅用于服务端与运维排查，前端不应依赖。
     */
    private String table;

    /**
     * 是否启用逻辑删除（存在 isDelete 列）。未启用时删除为物理删除。
     */
    private boolean softDelete;

    /**
     * 是否复合实体：主表行与子表行必须保持一致（当前仅路线）。
     */
    private boolean composite;

    /**
     * 子实体键（复合实体才有）
     */
    private String childEntityKey;

    /**
     * 子表中指向主表的列名（复合实体才有），如 routeId
     */
    private String childForeignKey;

    /**
     * 主表中由子表派生维护的列（复合实体才有），如 stationIds。该列不接受前端直接写入。
     */
    private List<String> derivedColumns;

    /**
     * 列元数据
     */
    private List<DataAdminColumnVo> columns;
}
