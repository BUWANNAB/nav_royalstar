package com.ant.robot.utils;

import java.util.UUID;

/**
 * UUID工具
 */
public class UuidUtils {

    /**
     * 得到32位的uuid
     */
    public static String uuid() {
        return UUID.randomUUID().toString().replaceAll("-", "");
    }
}
