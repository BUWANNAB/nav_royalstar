package com.ant.robot.common.aop;

import java.lang.annotation.*;

/**
 * @author ChenWeihan
 * @description 日志注解
 */
@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface LogAnnotation {

    /**
     * 模块标题
     */
    String title() default "";

    /**
     * 日志内容
     */
    String content() default "";
}