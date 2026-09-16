package com.ant.robot.config;

import com.alibaba.fastjson2.JSONObject;
import com.ant.robot.utils.JwtUtil;
import com.auth0.jwt.interfaces.Claim;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;

import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * @author ChenWeihan
 * @description jwt拦截
 */
@Component
public class JwtInterceptor implements HandlerInterceptor {
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {

        if (!(handler instanceof HandlerMethod)) {
            //当前拦截到的不是动态方法，直接放行
            return true;
        }
        String uri = request.getRequestURI();
        String headToken = request.getHeader("Authorization");

        if(StringUtils.isEmpty(headToken)) {
            Map<String, Object> map = new HashMap<>();
            map.put("code", 20001);
            map.put("message", "Missing or invalid Authorization header");
            ErrorResponse(response, map);
            return false;
        }
        try {
            Map<String, Claim> map = JwtUtil.verifyToken(headToken);
        } catch (Exception e) {
            Map<String, Object> map = new HashMap<>();
            map.put("code", 20002);
            map.put("message", "Invalid Authorization header " + e.getLocalizedMessage());
            ErrorResponse(response, map);
            return false;
        }
        return true;
    }

    @Override
    public void postHandle(HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse, Object o, ModelAndView modelAndView) throws Exception {
    }

    @Override
    public void afterCompletion(HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse, Object o, Exception e) throws Exception {
    }

    // 被拦截的请求响应
    private void ErrorResponse(HttpServletResponse response, Map<String, Object> result){
        OutputStream out = null;

        JSONObject object = new JSONObject(result);

        try{
            response.setCharacterEncoding("utf-8");
            response.setContentType("text/json");
            out = response.getOutputStream();
            out.write(object.toString().getBytes());
            out.flush();
        }catch (Exception e){
            e.printStackTrace();
        }finally{
            try {
                if (out != null) {
                    out.close();
                }
            }catch (Exception e){
                e.printStackTrace();
            }
        }
    }
}
