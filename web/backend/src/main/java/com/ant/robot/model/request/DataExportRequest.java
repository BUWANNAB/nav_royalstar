package com.ant.robot.model.request;

import lombok.Data;

/**
 * 数据导出请求（数据下载页）。
 *
 * @author ChenWeihan
 */
@Data
public class DataExportRequest {

    /**
     * 导出对象，取值：
     * sensorSql / sensorCsv / stationSql / routeSql / stationRouteSql
     */
    private String target;

    /**
     * 导出方式：download（浏览器下载）/ disk（写入工控机磁盘）
     */
    private String mode;

    /**
     * disk 模式的目标目录，留空则使用默认导出目录
     */
    private String dir;

    /**
     * disk 模式的文件名，留空则自动命名
     */
    private String filename;
}
