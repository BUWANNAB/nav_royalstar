package com.ant.robot.common.constants;

/**
 * @author ChenWeihan
 * @description 系统配置常量
 */
public interface SystemConstant {

    String INIT_POSE = "init_pose";

    String ROS_BAG_START = "rosbag start";

    String ROS_BAG_STOP = "rosbag stop";

    String FILE_TYPE_CSV = "csv";

    String FILE_TYPE_BAG = "bag";

    String FILE_TYPE_ZIP = "zip";

    String FILE_TYPE_TXT = "txt";

    String UTF_8 = "UTF-8";

    Long PER_SLICE = 1024 * 1024 * 50L;

    String RANGE = "Range";

    /**
     * 小文件最大大小: 2MB = 1024 * 1024 * 2
     */
    Long MAX_SIZE = 2097152L;


    /**
     * 定义1MB的字节数
     */
    Long MB_IN_BYTES = 1024L * 1024L;


    /**
     * 时区
     */
    String ZONE = "Asia/Shanghai";
}
