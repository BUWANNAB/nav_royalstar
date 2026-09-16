package com.ant.robot.service;

import com.ant.robot.model.domain.User;
import com.baomidou.mybatisplus.extension.service.IService;
import jakarta.servlet.http.HttpServletRequest;

/**
* @author 10345
* @description 针对表【t_user(用户)】的数据库操作Service
* @createDate 2024-04-04 16:10:25
*/
public interface UserService extends IService<User> {
    /**
     * 用户登录
     *
     * @param userAccount  用户账号
     * @param userPassword 用户密码
     * @return 脱敏后的用户信息
     */
    User userLogin(String userAccount, String userPassword);

    /**
     * 用户注册
     *
     * @param userAccount   用户账号
     * @param userPassword  用户密码
     * @param checkPassowrd 校验密码
     * @return 新用户id
     */
    long userRegister(String userAccount, String userPassword, String checkPassowrd);

    /**
     * 用户脱敏
     *
     * @param originUser 需要脱敏的用户
     * @return
     */
    User getSafetyUser(User originUser);


    /**
     * 用户注销
     *
     * @param request
     * @return
     */
    int userLogout(HttpServletRequest request);
}
