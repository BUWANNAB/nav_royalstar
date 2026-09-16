package com.ant.robot.config;

import jakarta.annotation.Resource;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.*;

/**
 * @author ChenWeihan
 * @description jwt配置拦截器 跨越
 */
@Configuration
public class JwtInterceptorConfig implements WebMvcConfigurer {

    @Resource
    private JwtInterceptor jwtInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 注册拦截器，要声明拦截器对象和要拦截的请求
        registry.addInterceptor(jwtInterceptor)
//                .excludePathPatterns("/ws/**")
                .excludePathPatterns("/**")
                //所有路径都被拦截
//                .addPathPatterns("/**")
                // 排除用户登录请求
                .excludePathPatterns("/user/login");
    }

    @Override
//    public void addCorsMappings(CorsRegistry registry) {
//        registry.addMapping("/**")
//                .allowedOrigins(
//                        "http://127.0.0.1",
//                        "http://localhost",
//                        "http://47.105.58.240")
//                .allowedHeaders("*")
//                //.exposedHeaders("*")
//                .allowCredentials(true)
//                .allowedMethods("*");
//                //.maxAge(3600);
//    }
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOriginPatterns("*")
                .allowedHeaders("*")
                //.exposedHeaders("*")
                .allowCredentials(true)
                .allowedMethods("*");
        //.maxAge(3600);
    }
}
