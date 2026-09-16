package com.ant.robot.model.request;

import lombok.Data;

/**
 * 数据导入请求（数据下载页）。
 *
 * @author ChenWeihan
 */
@Data
public class DataImportRequest {

    /**
     * 导入方式：upload（上传内容）/ disk（读取工控机磁盘文件）
     */
    private String mode;

    /**
     * upload 模式的 SQL 文本内容
     */
    private String content;

    /**
     * disk 模式的 .sql 绝对路径
     */
    private String path;

    /**
     * 是否允许脚本中包含 DROP TABLE / TRUNCATE（默认 false，命中即拒绝）
     */
    private Boolean allowDrop;
}
