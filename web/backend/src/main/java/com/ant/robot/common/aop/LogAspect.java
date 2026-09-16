package com.ant.robot.common.aop;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.ant.robot.mapper.LogMapper;
import com.ant.robot.model.domain.Log;
import com.ant.robot.model.domain.User;
import com.ant.robot.utils.JwtUtil;
import com.auth0.jwt.interfaces.Claim;
import jakarta.annotation.Resource;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.*;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.multipart.MultipartFile;

import java.lang.reflect.Method;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import static com.ant.robot.common.constants.UserConstant.USER_LOGIN_STATE;


/**
 * @author ChenWeihan
 * @description 日志切面
 */

@Aspect
@Component
@Slf4j
public class LogAspect {

    @Resource
    private LogMapper logMapper;

    /**
     * 记录方法的执行时间
     */
    ThreadLocal<Long> startTime = new ThreadLocal<>();

    /**
     * 设置操作日志切入点，这里介绍两种方式：
     * 1、基于注解切入（也就是打了自定义注解的方法才会切入）
     *
     * @Pointcut("@annotation(com.ant.robot.common.aop.LogAnnotation)")
     * 2、基于包扫描切入
     * @Pointcut("execution(public * com.ant.robot.controller..*.*(..))")
     */
    @Pointcut("@annotation(com.ant.robot.common.aop.LogAnnotation)")//在注解的位置切入代码
    public void operLogPoinCut() {
    }

    @Before("operLogPoinCut()")
    public void beforMethod(JoinPoint point) {
        startTime.set(System.currentTimeMillis());
    }

    /**
     * 设置操作异常切入点记录异常日志 扫描所有controller包下操作
     */
    @Pointcut("execution(* com.ant.robot.controller..*.*(..))")
    public void operExceptionLogPoinCut() {
    }

    /**
     * 正常返回通知，拦截用户操作日志，连接点正常执行完成后执行， 如果连接点抛出异常，则不会执行
     *
     * @param joinPoint 切入点
     * @param result    返回结果
     */
    @AfterReturning(value = "operLogPoinCut()", returning = "result")
    @Async
    public void saveOperLog(JoinPoint joinPoint, Object result) {
        // 获取RequestAttributes
        RequestAttributes requestAttributes = RequestContextHolder.getRequestAttributes();
        // 从获取RequestAttributes中获取HttpServletRequest的信息
        HttpServletRequest request = (HttpServletRequest) requestAttributes.resolveReference(RequestAttributes.REFERENCE_REQUEST);
        try {
            // 从切面织入点处通过反射机制获取织入点处的方法
            MethodSignature signature = (MethodSignature) joinPoint.getSignature();
            // 获取切入点所在的方法
            Method method = signature.getMethod();
            // 获取操作
            LogAnnotation logAnnotation = method.getAnnotation(LogAnnotation.class);

            Log operlog = new Log();
            if (logAnnotation != null) {
                //设置模块名称
                operlog.setTitle(logAnnotation.title());
                //设置日志内容
                operlog.setContent(logAnnotation.content());
            }
            // 将入参转换成json

            String params = filterParams(joinPoint.getArgs());

            // 获取请求的类名
            String className = joinPoint.getTarget().getClass().getName();
            // 获取请求的方法名
            String methodName = method.getName();
            methodName = className + "." + methodName + "()";
            //设置请求方法
            operlog.setMethod(methodName);

            //设置请求方式
            operlog.setRequestMethod(request.getMethod());
            // 请求参数
            operlog.setRequestParam(params);

            // 返回结果
            operlog.setReponseResult(JSON.toJSONString(result));

            // 从request中获取
            operlog.setUserAccount(getUserAccountByRequest(request));
            // IP地址
            operlog.setIp(getIp(request));
            // 请求URI
            operlog.setRequestUrl(request.getRequestURI());
            // 时间
            operlog.setOperTime(new Date());
            //操作状态（0正常 1异常）
            operlog.setStatus(0);
            //记录方法执行耗时时间（单位：毫秒）
            Long takeTime = System.currentTimeMillis() - startTime.get();
            operlog.setHoldTime(takeTime);
            //插入数据库
            logMapper.insert(operlog);
            startTime.remove();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 异常返回通知，用于拦截异常日志信息 连接点抛出异常后执行
     */
    @AfterThrowing(pointcut = "operExceptionLogPoinCut()", throwing = "e")
    public void saveExceptionLog(JoinPoint joinPoint, Throwable e) {
        // 获取RequestAttributes
        RequestAttributes requestAttributes = RequestContextHolder.getRequestAttributes();
        // 从获取RequestAttributes中获取HttpServletRequest的信息
        HttpServletRequest request = (HttpServletRequest) requestAttributes.resolveReference(RequestAttributes.REFERENCE_REQUEST);

        Log operlog = new Log();
        try {
            // 从切面织入点处通过反射机制获取织入点处的方法
            MethodSignature signature = (MethodSignature) joinPoint.getSignature();
            // 获取切入点所在的方法
            Method method = signature.getMethod();
            // 获取请求的类名
            String className = joinPoint.getTarget().getClass().getName();
            // 获取请求的方法名
            String methodName = method.getName();
            methodName = className + "." + methodName + "()";
            // 获取操作

            LogAnnotation logAnnotation = method.getAnnotation(LogAnnotation.class);

            if (logAnnotation != null) {
                //设置模块名称
                operlog.setTitle(logAnnotation.title());
                //设置日志内容
                operlog.setContent(logAnnotation.content());
            }
            // 将入参转换成json
            String params = filterParams(joinPoint.getArgs());
            //设置请求方法
            operlog.setMethod(methodName);
            //设置请求方式
            operlog.setRequestMethod(request.getMethod());
            // 请求参数
            operlog.setRequestParam(params);

            operlog.setUserAccount(getUserAccountByRequest(request));
            // IP地址
            operlog.setIp(getIp(request));
            // 请求URI
            operlog.setRequestUrl(request.getRequestURI());
            // 时间
            operlog.setOperTime(new Date());
            //操作状态（0正常 1异常）
            operlog.setStatus(1);
            operlog.setErrorMsg(stackTraceToString(e.getClass().getName(), e.getMessage(), e.getStackTrace()));//记录异常信息

            //插入数据库
            logMapper.insert(operlog);
        } catch (Exception e2) {
            e2.printStackTrace();
        }
    }

    /**
     * 转换异常信息为字符串
     */
    public String stackTraceToString(String exceptionName, String exceptionMessage, StackTraceElement[] elements) {
        StringBuffer strbuff = new StringBuffer();
        for (StackTraceElement stet : elements) {
            strbuff.append(stet + "\n");
        }
        String message = exceptionName + ":" + exceptionMessage + "\n\t" + strbuff.toString();
        message = substring(message, 0, 2000);
        return message;
    }

    /**
     * 过滤 ServletRequest ServletResponse MultipartFile
     * @param args
     * @return
     */
    private String filterParams(Object[] args) {
        Object[] arguments  = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            if (args[i] instanceof ServletRequest || args[i] instanceof ServletResponse || args[i] instanceof MultipartFile) {
                continue;
            }
            arguments[i] = args[i];
        }
        String paramter = JSONObject.toJSONString(arguments);
        return paramter;
    }
    /**
     * 参数拼装
     */
    private String argsArrayToString(Object[] paramsArray) {
        String params = "";
        if (paramsArray != null && paramsArray.length > 0) {
            for (Object o : paramsArray) {
                if (o != null) {
                    try {
                        Object jsonObj = JSON.toJSON(o);
                        params += jsonObj.toString() + " ";
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }
        }
        return params.trim();
    }

    //字符串截取
    public static String substring(String str, int start, int end) {
        if (str == null) {
            return null;
        } else {
            if (end < 0) {
                end += str.length();
            }

            if (start < 0) {
                start += str.length();
            }

            if (end > str.length()) {
                end = str.length();
            }

            if (start > end) {
                return "";
            } else {
                if (start < 0) {
                    start = 0;
                }

                if (end < 0) {
                    end = 0;
                }
                return str.substring(start, end);
            }
        }
    }

    /**
     * 转换request 请求参数
     *
     * @param paramMap request获取的参数数组
     */
    public Map<String, String> converMap(Map<String, String[]> paramMap) {
        Map<String, String> returnMap = new HashMap<>();
        for (String key : paramMap.keySet()) {
            returnMap.put(key, paramMap.get(key)[0]);
        }
        return returnMap;
    }

    //根据HttpServletRequest获取访问者的IP地址
    public static String getIp(HttpServletRequest request) {
        String ip = request.getHeader("x-forwarded-for");
        if (ip == null || ip.length() == 0 || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("Proxy-Client-IP");
        }
        if (ip == null || ip.length() == 0 || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("WL-Proxy-Client-IP");
        }
        if (ip == null || ip.length() == 0 || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("HTTP_CLIENT_IP");
        }
        if (ip == null || ip.length() == 0 || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("HTTP_X_FORWARDED_FOR");
        }
        if (ip == null || ip.length() == 0 || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        return ip;
    }

    public static String getUserAccountByRequest(HttpServletRequest request) {
        String userAccount = "";
        String headToken = request.getHeader("Authorization");
        if (StringUtils.isEmpty(headToken)) {
            return userAccount;
        }
        Claim claim = JwtUtil.parseToken(headToken).get("userAccount");
        if (claim != null) {
            userAccount = claim.asString();
        }
        return userAccount;
    }
}
