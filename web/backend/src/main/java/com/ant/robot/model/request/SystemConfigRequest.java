package com.ant.robot.model.request;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 系统配置
 */
@Data
public class SystemConfigRequest implements Serializable {

    private static final long serialVersionUID = -2484387651368328894L;
    /**
     * 配置key
     */
    private String configKey;

    /**
     * 配置value
     */
    private String configValue;

}