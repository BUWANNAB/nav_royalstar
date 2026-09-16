package com.ant.robot.common.constants;

/**
 * @author ChenWeihan
 */
public interface UserConstant {


    /**
     * 用户登录状态
     */
    String USER_LOGIN_STATE = "userLoginState";

    /**
     * 0-普通用户 1-管理员
     */
    int DEFAULT_ROLE = 0;
    int ADMIN_ROLE = 1;

    /**
     * 密码混淆盐值。
     *
     * <p>口令哈希算法为 {@code md5(PASSWORD_SALT + 明文)}，32 位小写十六进制。
     * 该盐值原先只作为 {@code UserServiceImpl} 的私有字段存在，
     * 现收敛到此处作为单一来源，避免数据管理页等新入口复刻出不一致的哈希。</p>
     */
    String PASSWORD_SALT = "ant-robot";
}
