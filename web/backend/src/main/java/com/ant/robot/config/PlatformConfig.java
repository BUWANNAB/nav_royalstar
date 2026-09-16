package com.ant.robot.config;

public class PlatformConfig {
    public static final boolean IS_WINDOWS = System.getProperty("os.name").toLowerCase().contains("windows");
    public static final boolean IS_LINUX = System.getProperty("os.name").toLowerCase().contains("linux");
}
