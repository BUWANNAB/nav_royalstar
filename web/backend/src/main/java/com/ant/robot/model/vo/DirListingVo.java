package com.ant.robot.model.vo;

import lombok.Data;

import java.util.List;

/**
 * 目录浏览结果。
 *
 * @author ChenWeihan
 */
@Data
public class DirListingVo {

    /**
     * 当前目录绝对路径
     */
    private String path;

    /**
     * 上级目录绝对路径，已到根则为 null
     */
    private String parent;

    /**
     * 是否已到可浏览的根
     */
    private Boolean isRoot;

    /**
     * 子目录名列表
     */
    private List<String> dirs;

    /**
     * .sql 文件名列表（仅导入模式返回）
     */
    private List<String> files;
}
