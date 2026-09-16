package com.ant.robot.model.vo;

import lombok.Data;

import java.util.Date;

/**
 * @author ChenWeihan
 * @description TODO
 */
@Data
public class TableFileVo {
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
    /**
     * 创建时间
     */
    private Date createdTime;
}
