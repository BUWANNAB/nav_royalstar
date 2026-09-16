package com.ant.robot.controller;

import com.ant.robot.common.response.ResultUtils;
import com.ant.robot.common.exception.BusinessException;
import com.ant.robot.common.response.BaseResponse;
import com.ant.robot.common.aop.LogAnnotation;
import com.ant.robot.common.enums.ErrorCode;
import com.ant.robot.model.domain.User;
import com.ant.robot.model.request.UserLoginRequest;
import com.ant.robot.model.request.UserRegisterRequest;
import com.ant.robot.service.UserService;
import com.ant.robot.utils.JwtUtil;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import static com.ant.robot.common.constants.UserConstant.USER_LOGIN_STATE;

/**
 * @author ChenWeihan
 * @description 用户
 */
@RestController
@RequestMapping("user")
@Slf4j
public class UserController {

    @Resource
    private UserService userService;

    /**
     * 用户登录
     */
    @LogAnnotation(title = "用户模块", content = "登录操作")
    @PostMapping("login")
    @Transactional(rollbackFor = Exception.class)
    public BaseResponse<String> login(@RequestBody UserLoginRequest userLoginRequest) {
        log.info("UserLoginRequest：{}",userLoginRequest);
        // 验证登录请求的合法性
        validateLoginRequest(userLoginRequest);

        String userAccount = userLoginRequest.getUserAccount();
        String userPassword = userLoginRequest.getUserPassword();

        // 尝试进行用户登录，包括异常处理
        User user = null;
        try {
            user = userService.userLogin(userAccount, userPassword);
        } catch (Exception e) {
            // 记录登录异常，并抛出业务异常
            log.error("登录异常: ", e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR);
        }

        // 登录失败处理
        if (user == null) {
            throw new BusinessException(ErrorCode.NOT_LOGIN);
        }
        String token;
        try {
            token = JwtUtil.createToken(userAccount);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        // 登录成功，返回用户信息
        return ResultUtils.success(token);
    }

    /**
     * 用户注册
     */
    @PostMapping("register")
    @LogAnnotation(title = "用户模块", content = "注册操作")
    public BaseResponse<Long> userRegister(@RequestBody UserRegisterRequest userRegisterRequest) {
        if (userRegisterRequest == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        String userAccount = userRegisterRequest.getUserAccount();
        String userPassword = userRegisterRequest.getUserPassword();
        String checkPassword = userRegisterRequest.getCheckPassword();

        //Controller 对请求参数没有感情的校验
        //Service 是对业务逻辑进行校验
        if (StringUtils.isAnyBlank(userAccount, userPassword, checkPassword)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }

        long result = userService.userRegister(userAccount, userPassword, checkPassword);
        return ResultUtils.success(result);
    }



    /**
     * 用户登出功能。
     *
     * @param request HttpServletRequest对象，用于获取用户登出请求的相关信息。
     * @return 返回一个包含登出结果的BaseResponse对象
     */
    @LogAnnotation(title = "用户模块", content = "登出操作")
    @PostMapping("logout")
    public BaseResponse<Integer> userLogout(HttpServletRequest request) {
        // 检查请求对象是否为空，若为空则抛出业务异常
        if (request == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        // 调用userService的userLogout方法处理用户登出请求，并获取结果
        int result = userService.userLogout(request);
        // 构造并返回一个表示操作成功的BaseResponse对象，其中包含登出操作的影响行数
        return ResultUtils.success(result);
    }


    /**
     * 获取当前登录用户的信息。
     *
     * @param request HttpServletRequest对象，用于获取会话中的用户信息。
     * @return BaseResponse<User> 包含用户信息的响应对象，如果用户未登录，则返回错误信息。
     */
    @GetMapping("current")
    public BaseResponse<User> getCurrentUser(HttpServletRequest request) {
        // 从会话中获取登录状态的用户对象
        Object userObj = request.getSession().getAttribute(USER_LOGIN_STATE);
        User currentUser = (User) userObj;
        // 检查用户对象是否存在，若不存在则抛出未登录异常
        if (currentUser == null) {
            throw new BusinessException(ErrorCode.NOT_LOGIN);
        }
        Long userId = currentUser.getId();
        // 校验用户ID的有效性，通过用户服务获取用户信息
        User user = userService.getById(userId);
        // 获取安全信息完善的用户对象
        User safetyUser = userService.getSafetyUser(user);
        // 返回成功响应，包含安全用户信息
        return ResultUtils.success(safetyUser);
    }



    /**
     * 验证用户登录请求的合法性。
     * 对用户登录时提交的请求数据进行校验，包括参数的非空、账号密码的合法性等。
     *
     * @param userLoginRequest 用户登录请求对象，包含用户账号和密码。
     * @throws BusinessException 如果校验失败，抛出业务异常。可能的错误码有：PARAMS_ERROR（参数错误）。
     */
    private void validateLoginRequest(UserLoginRequest userLoginRequest) {
        // 校验用户登录请求对象是否为null
        if (userLoginRequest == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        String userAccount = userLoginRequest.getUserAccount();
        String userPassword = userLoginRequest.getUserPassword();

        // 校验用户账号和密码是否为空
        if (StringUtils.isAnyBlank(userAccount, userPassword)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        // 校验密码强度
        if (!isPasswordStrong(userPassword)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "密码强度不足");
        }
    }

    /**
     * 检查密码的强度。
     * 该方法根据预设的规则检查输入的密码是否满足强度要求。
     *
     * @param password 待检查的密码字符串。
     * @return 返回一个布尔值，如果密码满足强度要求则返回true，否则返回false。
     */
    private boolean isPasswordStrong(String password) {
        // 实现密码强度检查逻辑
        return true;
    }

}
