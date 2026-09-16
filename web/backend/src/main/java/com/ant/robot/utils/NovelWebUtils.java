package com.ant.robot.utils;

import com.ant.robot.common.enums.ErrorCode;
import com.ant.robot.common.response.BaseResponse;
import com.ant.robot.common.response.ResultUtils;

/**
 * 提供给NovelWeb工具的相关工具类
 *
 */
public class NovelWebUtils {
    /**
     * 上传文件结果转换为本系统的结果
     *
     * @param result 结果
     */
    public static BaseResponse<Object> forReturn(cn.novelweb.tool.http.Result<Object> result) {
        if ("200".equals(result.getCode()) || "201".equals(result.getCode())) {
            return ResultUtils.error(null, result.getMessage());
        } else if ("206".equals(result.getCode())) {
            return ResultUtils.error(ErrorCode.PARAMS_ERROR, result.getData() + result.getMessage());
        } else {
            return ResultUtils.error(ErrorCode.PARAMS_ERROR, result.getMessage());
        }
    }

}
