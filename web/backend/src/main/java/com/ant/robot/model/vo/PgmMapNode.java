package com.ant.robot.model.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class PgmMapNode {

    /**
     * 地图名称 (例如: "level_1_office")
     */
    private String name;

    /**
     * 相对路径 (例如: "level_1_office/level_1_edited")
     * 这是地图的唯一ID，用于下载或保存
     */
    private String path;

    /**
     * 子地图 (另存为的地图)
     */
    private List<PgmMapNode> children;

    public PgmMapNode(String name, String path) {
        this.name = name;
        this.path = path;
        this.children = new ArrayList<>();
    }
}

