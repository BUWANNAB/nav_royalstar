package com.ant.robot.controller;

import com.ant.robot.common.enums.ErrorCode;
import com.ant.robot.common.response.BaseResponse;
import com.ant.robot.common.response.ResultUtils;
import com.ant.robot.config.DataAdminRegistry;
import com.ant.robot.model.request.DataAdminQueryRequest;
import com.ant.robot.model.request.DataAdminWriteRequest;
import com.ant.robot.model.vo.DataAdminDetailVo;
import com.ant.robot.model.vo.DataAdminEntityVo;
import com.ant.robot.model.vo.DataAdminPageVo;
import com.ant.robot.service.DataAdminService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 数据管理页通用接口（元数据驱动）。
 *
 * <p>站点、路线、路线明细等实体的增删改查都走这里，实体与列的可写范围由
 * {@link DataAdminRegistry} 白名单决定，前端无法指定表名或列名。</p>
 *
 * <p>注意：本接口与其他接口一致，当前<b>不做鉴权</b>
 * （JwtInterceptor 配置为 {@code excludePathPatterns("/**")}）。</p>
 *
 * @author ChenWeihan
 */
@RestController
@RequestMapping("data-admin")
@Slf4j
public class DataAdminController {

    @Resource
    private DataAdminRegistry registry;

    @Resource
    private DataAdminService dataAdminService;

    /**
     * 全部实体元数据，供前端渲染标签页。
     */
    @GetMapping("entities")
    public BaseResponse<List<DataAdminEntityVo>> entities() {
        return ResultUtils.success(registry.allVos());
    }

    /**
     * 单个实体元数据。
     */
    @GetMapping("{entity}/meta")
    public BaseResponse<DataAdminEntityVo> meta(@PathVariable("entity") String entity) {
        return ResultUtils.success(registry.toVo(registry.require(entity)));
    }

    /**
     * 分页查询。用 POST 是因为筛选条件是一个 map，放 body 更自然。
     */
    @PostMapping("{entity}/list")
    public BaseResponse<DataAdminPageVo> list(@PathVariable("entity") String entity,
                                              @RequestBody(required = false) DataAdminQueryRequest request) {
        return ResultUtils.success(dataAdminService.list(entity, request));
    }

    /**
     * 单条详情（复合实体连带子行）。
     */
    @GetMapping("{entity}/{id}")
    public BaseResponse<DataAdminDetailVo> detail(@PathVariable("entity") String entity,
                                                  @PathVariable("id") Long id,
                                                  @RequestParam(value = "includeDeleted", defaultValue = "false")
                                                  boolean includeDeleted) {
        DataAdminDetailVo vo = new DataAdminDetailVo();
        vo.setRow(dataAdminService.getById(entity, id, includeDeleted));
        vo.setDetails(dataAdminService.listChildren(entity, id, includeDeleted));
        return ResultUtils.success(vo);
    }

    /**
     * 新增。返回新行主键。
     */
    @PostMapping("{entity}")
    public BaseResponse<Object> create(@PathVariable("entity") String entity,
                                       @RequestBody DataAdminWriteRequest request) {
        return ResultUtils.success(dataAdminService.create(entity, request));
    }

    /**
     * 修改（部分更新：只写请求中出现的列）。
     */
    @PutMapping("{entity}/{id}")
    public BaseResponse<Integer> update(@PathVariable("entity") String entity,
                                        @PathVariable("id") Long id,
                                        @RequestBody DataAdminWriteRequest request) {
        return ResultUtils.success(dataAdminService.update(entity, id, request));
    }

    /**
     * 删除（存在 isDelete 列为逻辑删除，否则物理删除）。
     */
    @DeleteMapping("{entity}/{id}")
    public BaseResponse<Integer> delete(@PathVariable("entity") String entity,
                                        @PathVariable("id") Long id) {
        return ResultUtils.success(dataAdminService.delete(entity, id));
    }

    /**
     * 参数类异常统一转成 40000，避免把内部细节抛成 500。
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public BaseResponse<Object> handleIllegalArgument(IllegalArgumentException e) {
        log.warn("数据管理请求被拒绝: {}", e.getMessage());
        return ResultUtils.error(ErrorCode.PARAMS_ERROR, e.getMessage());
    }
}
