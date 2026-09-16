package com.ant.robot.model.request;

import lombok.Data;

/**
 * 新建目录请求（数据下载页目录浏览器）。
 *
 * @author ChenWeihan
 */
@Data
public class MkdirRequest {

    /**
     * 父目录，留空则使用默认导出目录
     */
    private String path;

    /**
     * 新建的文件夹名
     */
    private String name;
}
