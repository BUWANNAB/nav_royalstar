package com.ant.robot.model.request;

import lombok.Data;

import java.util.Date;

/**
 * @author ChenWeihan
 */
@Data
public class FileRequest {
    /**
     * 文件ID
     */
    private String id;
    /**
     * md5值
     */
    private String md5;
    /**
     * 文件位置
     */
    private String filePath;
    /**
     * 原始文件名
     */
    private String fileName;
    /**
     * 文件后缀
     */
    private String suffix;
}
