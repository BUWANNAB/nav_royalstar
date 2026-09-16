package com.ant.robot.model.vo;

import lombok.Data;
import lombok.Getter;
import lombok.Setter;

/**
 * @author ChenWeihan
 * @description TODO
 */
@Data
@Getter
@Setter
public class FileVo {
    private long fileNo;
    private String fileName;
    private String fileType;
    private double fileSize;
}
