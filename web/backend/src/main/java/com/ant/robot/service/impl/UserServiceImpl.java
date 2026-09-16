package com.ant.robot.service.impl;

import com.ant.robot.common.enums.ErrorCode;
import com.ant.robot.common.exception.BusinessException;
import com.ant.robot.mapper.UserMapper;
import com.ant.robot.model.domain.User;
import com.ant.robot.service.UserService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.ant.robot.common.constants.UserConstant.PASSWORD_SALT;
import static com.ant.robot.common.constants.UserConstant.USER_LOGIN_STATE;

/**
* @author 10345
* @description 针对表【t_user(用户)】的数据库操作Service实现
* @createDate 2024-04-04 16:10:25
*/
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User>
    implements UserService {

    /**
     * 盐值 混淆密码
     *
     * <p>取值来自 {@code UserConstant.PASSWORD_SALT}（单一来源），
     * 行为与原先的字面量 "ant-robot" 完全一致。</p>
     */
    private final String SALT = PASSWORD_SALT;

    /**
     * 账号最小长度
     */
    private final int minUserAccountLength = 4;
    /**
     * 密码最小长度
     */
    private final int minPasswordLength = 6;

    @Resource
    private UserMapper userMapper;

    /**
     * 用户登录
     *
     * @param userAccount  用户账号
     * @param userPassword 用户密码
     * @return 脱敏后的用户信息
     */
    @Override
    public User userLogin(String userAccount, String userPassword) {
        // 1.校验
        if (StringUtils.isAnyBlank(userAccount, userPassword)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }


        // 2.加密
        String encryptPassowrd = DigestUtils.md5DigestAsHex((SALT + userPassword).getBytes());

        // 3.查询用户是否存在
        QueryWrapper<User> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("userAccount", userAccount);
        queryWrapper.eq("userPassword", encryptPassowrd);
        User user = userMapper.selectOne(queryWrapper);

        // 用户不存在
        if (user == null) {
            log.info("user login failed, userAccount cannot match userPassword");
            return null;
        }

        // 脱敏
        User safetyUser = getSafetyUser(user);

        // 4. 记录用户登录状态
        return safetyUser;
    }

    /**
     * 用户注册
     *
     * @param userAccount   用户账号
     * @param userPassword  用户密码
     * @param checkPassowrd 校验密码
     * @return
     */
    @Override
    public long userRegister(String userAccount, String userPassword, String checkPassowrd) {
        // 1.校验
        if (StringUtils.isAnyBlank(userAccount, userPassword, checkPassowrd)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "参数为空");
        }
        if (userAccount.length() < minUserAccountLength) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "用户账号过短");
        }
        if (userPassword.length() < minPasswordLength || checkPassowrd.length() < minPasswordLength) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "用户密码过短");
        }
        // 账户不能包含特殊字符
        String validPattern = "^[a-zA-Z0-9_]+$";
        Matcher matcher = Pattern.compile(validPattern).matcher(userAccount);
        if (!matcher.find()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "账户包含特殊字符");
        }
        if (!userPassword.equals(checkPassowrd)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "两次密码不一致");
        }
        // 账户不能重复
        QueryWrapper<User> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("userAccount", userAccount);
        long count = userMapper.selectCount(queryWrapper);
        if (count > 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "账号重复");
        }

        // 2.加密
        String encryptPassword = DigestUtils.md5DigestAsHex((SALT + userPassword).getBytes());

        // 3.插入数据
        User user = new User();
        user.setUserAccount(userAccount);
        user.setUserPassword(encryptPassword);
        boolean saveResult = this.save(user);
        if (!saveResult) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR);
        }
        return user.getId();
    }

    /**
     * 用户脱敏
     *
     * @param originUser 需要脱敏的用户
     */
    @Override
    public User getSafetyUser(User originUser) {
        if (originUser == null) {
            return null;
        }
        User safetyUser = new User();
        safetyUser.setId(originUser.getId());
        safetyUser.setUserAccount(originUser.getUserAccount());
        safetyUser.setCreateTime(originUser.getCreateTime());
        return safetyUser;
    }

    /**
     * 用户注销
     */
    @Override
    public int userLogout(HttpServletRequest request) {
        //移除登录状态
        request.getSession().removeAttribute(USER_LOGIN_STATE);
        return 1;
    }
}




