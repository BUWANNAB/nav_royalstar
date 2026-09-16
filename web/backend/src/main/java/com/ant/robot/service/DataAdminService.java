package com.ant.robot.service;

import com.alibaba.fastjson.JSON;
import com.ant.robot.config.DataAdminRegistry;
import com.ant.robot.model.request.DataAdminQueryRequest;
import com.ant.robot.model.request.DataAdminWriteRequest;
import com.ant.robot.model.vo.DataAdminPageVo;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.DigestUtils;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.ant.robot.common.constants.UserConstant.PASSWORD_SALT;

/**
 * 数据管理页通用 CRUD 服务（元数据驱动）。
 *
 * <p>安全约束：</p>
 * <ol>
 *   <li>表名与列名<b>只</b>来自 {@link DataAdminRegistry}，前端传入的实体键与列名都要经过校验，
 *       无法注入任意表名/列名。</li>
 *   <li>所有值一律用 {@code ?} 占位符参数绑定，不拼接进 SQL。</li>
 *   <li>再叠加一道标识符白名单正则，作为防御纵深。</li>
 * </ol>
 *
 * @author ChenWeihan
 */
@Service
@Slf4j
public class DataAdminService {

    /** 每页最大行数，防止前端一次拉全表 */
    private static final long MAX_PAGE_SIZE = 200L;

    /**
     * 脱敏占位符。敏感列读取时返回该值；
     * 写入时若回传该值，视为「未修改」而跳过，避免把占位符当成明文口令哈希掉。
     */
    static final String MASK_SENTINEL = "******";

    /** 标识符白名单：只允许字母、数字、下划线，且必须字母或下划线开头 */
    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");

    @Resource
    private DataAdminRegistry registry;

    @Resource
    private RouteDirectionService routeDirectionService;

    @Resource
    private DataSource dataSource;

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(dataSource);
    }

    // ==================== 查询 ====================

    /**
     * 分页查询。
     */
    public DataAdminPageVo list(String entityKey, DataAdminQueryRequest request) {
        DataAdminRegistry.EntityMeta m = registry.require(entityKey);
        DataAdminQueryRequest req = request == null ? new DataAdminQueryRequest() : request;

        String columnSql = m.getColumns().stream().map(DataAdminRegistry.ColumnMeta::getName)
                .map(DataAdminService::quote).collect(Collectors.joining(", "));
        Where where = buildWhere(m, req);

        long total = queryCount(m, where);
        long pageSize = clampPageSize(req.getPageSize());
        long pageNum = req.getPageNum() == null || req.getPageNum() < 1 ? 1L : req.getPageNum();

        String orderSql = buildOrder(m, req);

        List<Object> args = new ArrayList<>(where.args);
        args.add(pageSize);
        args.add((pageNum - 1) * pageSize);

        String sql = "SELECT " + columnSql + " FROM " + quote(m.getTable())
                + where.sql + orderSql + " LIMIT ? OFFSET ?";

        List<Map<String, Object>> rows = jdbc().query(sql, (rs, rowNum) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            for (DataAdminRegistry.ColumnMeta c : m.getColumns()) {
                Object v = rs.getObject(c.getName());
                if (c.isMasked() && v != null) {
                    v = MASK_SENTINEL;
                }
                row.put(c.getName(), v);
            }
            return row;
        }, args.toArray());

        DataAdminPageVo vo = new DataAdminPageVo();
        vo.setTotal(total);
        vo.setPageNum(pageNum);
        vo.setPageSize(pageSize);
        vo.setRows(rows);
        return vo;
    }

    /**
     * 按主键查单条。
     */
    public Map<String, Object> getById(String entityKey, Object id, boolean includeDeleted) {
        DataAdminRegistry.EntityMeta m = registry.require(entityKey);
        String columnSql = m.getColumns().stream().map(DataAdminRegistry.ColumnMeta::getName)
                .map(DataAdminService::quote).collect(Collectors.joining(", "));

        StringBuilder sql = new StringBuilder("SELECT ").append(columnSql)
                .append(" FROM ").append(quote(m.getTable()))
                .append(" WHERE ").append(quote(m.getIdColumn())).append(" = ?");
        if (m.isSoftDelete() && !includeDeleted) {
            sql.append(" AND isDelete = 0");
        }

        List<Map<String, Object>> rows = jdbc().query(sql.toString(), (rs, rowNum) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            for (DataAdminRegistry.ColumnMeta c : m.getColumns()) {
                Object v = rs.getObject(c.getName());
                if (c.isMasked() && v != null) {
                    v = MASK_SENTINEL;
                }
                row.put(c.getName(), v);
            }
            return row;
        }, id);

        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 查复合实体的子行（按子表主键排序，代表顺序）。
     */
    public List<Map<String, Object>> listChildren(String parentEntityKey, Object parentId, boolean includeDeleted) {
        DataAdminRegistry.EntityMeta parent = registry.require(parentEntityKey);
        if (!parent.isComposite()) {
            return Collections.emptyList();
        }
        DataAdminRegistry.EntityMeta child = registry.require(parent.getChildEntityKey());

        String columnSql = child.getColumns().stream().map(DataAdminRegistry.ColumnMeta::getName)
                .map(DataAdminService::quote).collect(Collectors.joining(", "));

        StringBuilder sql = new StringBuilder("SELECT ").append(columnSql)
                .append(" FROM ").append(quote(child.getTable()))
                .append(" WHERE ").append(quote(parent.getChildForeignKey())).append(" = ?");
        if (child.isSoftDelete() && !includeDeleted) {
            sql.append(" AND isDelete = 0");
        }
        sql.append(" ORDER BY ").append(quote(child.getIdColumn()));

        return jdbc().query(sql.toString(), (rs, rowNum) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            for (DataAdminRegistry.ColumnMeta c : child.getColumns()) {
                row.put(c.getName(), rs.getObject(c.getName()));
            }
            return row;
        }, parentId);
    }

    // ==================== 写入 ====================

    /**
     * 新增。返回新行主键。
     */
    @Transactional(rollbackFor = Exception.class)
    public Object create(String entityKey, DataAdminWriteRequest request) {
        DataAdminRegistry.EntityMeta m = registry.require(entityKey);
        Map<String, Object> values = sanitizeValues(m, request, true);

        if (m.isComposite()) {
            // 复合实体：先由子行推导派生列，再插主表，再插子表
            List<Map<String, Object>> details = request.getDetails();
            List<Long> stationIds = extractStationIds(m, details);
            if (stationIds != null) {
                values.put("stationIds", JSON.toJSONString(stationIds));
            }
            Object id = insertRow(m, values);
            replaceChildren(m, id, details, stationIds, Boolean.TRUE.equals(request.getRecomputeDirection()));
            log.info("数据管理新增 {} id={}", entityKey, id);
            return id;
        }

        Object id = insertRow(m, values);
        log.info("数据管理新增 {} id={}", entityKey, id);
        return id;
    }

    /**
     * 修改（部分更新：只写 values 中出现的列）。
     */
    @Transactional(rollbackFor = Exception.class)
    public int update(String entityKey, Object id, DataAdminWriteRequest request) {
        DataAdminRegistry.EntityMeta m = registry.require(entityKey);
        Map<String, Object> values = sanitizeValues(m, request, false);

        int affected;
        if (m.isComposite() && request.getDetails() != null) {
            List<Map<String, Object>> details = request.getDetails();
            List<Long> stationIds = extractStationIds(m, details);
            if (stationIds != null) {
                values.put("stationIds", JSON.toJSONString(stationIds));
            }
            affected = updateRow(m, id, values);
            replaceChildren(m, id, details, stationIds, Boolean.TRUE.equals(request.getRecomputeDirection()));
        } else {
            affected = updateRow(m, id, values);
        }
        log.info("数据管理修改 {} id={} 影响 {} 行", entityKey, id, affected);
        return affected;
    }

    /**
     * 删除：有 isDelete 列则逻辑删除，否则物理删除。复合实体连带处理子行。
     */
    @Transactional(rollbackFor = Exception.class)
    public int delete(String entityKey, Object id) {
        DataAdminRegistry.EntityMeta m = registry.require(entityKey);

        if (m.isComposite()) {
            DataAdminRegistry.EntityMeta child = registry.require(m.getChildEntityKey());
            if (child.isSoftDelete()) {
                jdbc().update("UPDATE " + quote(child.getTable()) + " SET isDelete = 1 WHERE "
                        + quote(m.getChildForeignKey()) + " = ? AND isDelete = 0", id);
            } else {
                jdbc().update("DELETE FROM " + quote(child.getTable()) + " WHERE "
                        + quote(m.getChildForeignKey()) + " = ?", id);
            }
        }

        int affected;
        if (m.isSoftDelete()) {
            affected = jdbc().update("UPDATE " + quote(m.getTable()) + " SET isDelete = 1 WHERE "
                    + quote(m.getIdColumn()) + " = ? AND isDelete = 0", id);
        } else {
            affected = jdbc().update("DELETE FROM " + quote(m.getTable()) + " WHERE "
                    + quote(m.getIdColumn()) + " = ?", id);
        }
        log.info("数据管理删除 {} id={} 影响 {} 行（{})", entityKey, id, affected,
                m.isSoftDelete() ? "逻辑删除" : "物理删除");
        return affected;
    }

    // ==================== 内部：SQL 片段 ====================

    /** WHERE 子句及其参数。包级可见以便单元测试。 */
    static class Where {
        String sql = "";
        List<Object> args = new ArrayList<>();
    }

    static Where buildWhere(DataAdminRegistry.EntityMeta m, DataAdminQueryRequest req) {
        Where w = new Where();
        List<String> conds = new ArrayList<>();

        if (m.isSoftDelete() && !Boolean.TRUE.equals(req.getIncludeDeleted())) {
            conds.add("isDelete = 0");
        }

        if (req.getFilters() != null) {
            for (Map.Entry<String, String> e : req.getFilters().entrySet()) {
                String col = e.getKey();
                String kw = e.getValue();
                if (StringUtils.isBlank(kw)) {
                    continue;
                }
                DataAdminRegistry.ColumnMeta c = m.column(col);
                if (c == null || !c.isFilterable()) {
                    throw new IllegalArgumentException("不支持筛选的列: " + col);
                }
                assertSafeIdentifier(col);
                // 字符串类列走模糊匹配，其余走等值
                boolean like = DataAdminRegistry.TYPE_STRING.equals(c.getType())
                        || DataAdminRegistry.TYPE_SELECT.equals(c.getType())
                        || DataAdminRegistry.TYPE_JSON.equals(c.getType());
                if (like) {
                    conds.add(quote(col) + " LIKE ?");
                    w.args.add("%" + kw.trim() + "%");
                } else {
                    conds.add(quote(col) + " = ?");
                    w.args.add(kw.trim());
                }
            }
        }

        if (!conds.isEmpty()) {
            w.sql = " WHERE " + String.join(" AND ", conds);
        }
        return w;
    }

    private long queryCount(DataAdminRegistry.EntityMeta m, Where where) {
        String sql = "SELECT COUNT(*) FROM " + quote(m.getTable()) + where.sql;
        Long n = jdbc().queryForObject(sql, Long.class, where.args.toArray());
        return n == null ? 0L : n;
    }

    static String buildOrder(DataAdminRegistry.EntityMeta m, DataAdminQueryRequest req) {
        String sortBy = req.getSortBy();
        if (StringUtils.isNotBlank(sortBy)) {
            DataAdminRegistry.ColumnMeta c = m.column(sortBy);
            if (c == null || !c.isSortable()) {
                throw new IllegalArgumentException("不支持排序的列: " + sortBy);
            }
            assertSafeIdentifier(sortBy);
            boolean asc = "asc".equalsIgnoreCase(StringUtils.defaultString(req.getSortOrder()));
            return " ORDER BY " + quote(sortBy) + (asc ? " ASC" : " DESC");
        }
        // 默认按主键倒序，保证分页稳定
        return " ORDER BY " + quote(m.getIdColumn()) + " DESC";
    }

    // ==================== 内部：写入辅助 ====================

    /**
     * 过滤出可写列。createMode=true 时额外校验必填列。
     */
    static Map<String, Object> sanitizeValues(DataAdminRegistry.EntityMeta m,
                                              DataAdminWriteRequest request,
                                              boolean createMode) {
        Map<String, Object> raw = request == null || request.getValues() == null
                ? Collections.emptyMap() : request.getValues();
        Map<String, Object> out = new LinkedHashMap<>();

        for (Map.Entry<String, Object> e : raw.entrySet()) {
            String col = e.getKey();
            DataAdminRegistry.ColumnMeta c = m.column(col);
            if (c == null) {
                throw new IllegalArgumentException("未知的列: " + col);
            }
            if (!m.isWritable(col)) {
                throw new IllegalArgumentException("列不可写: " + col
                        + (m.getDerivedColumns().contains(col) ? "（该列由明细自动维护）" : ""));
            }
            assertSafeIdentifier(col);
            Object v = normalizeValue(e.getValue());

            if (c.getWriteEncoder() != null) {
                // 口令类字段：留空、或回传了脱敏占位符，都表示「不修改」
                if (v == null || MASK_SENTINEL.equals(v)) {
                    continue;
                }
                v = encodeValue(c.getWriteEncoder(), v);
            }

            out.put(col, v);
        }

        if (createMode) {
            for (DataAdminRegistry.ColumnMeta c : m.getColumns()) {
                if (c.isRequired() && !out.containsKey(c.getName())) {
                    throw new IllegalArgumentException("缺少必填列: " + c.getLabel() + "(" + c.getName() + ")");
                }
            }
        }

        if (!createMode && out.isEmpty() && (request == null || request.getDetails() == null)) {
            throw new IllegalArgumentException("没有需要更新的列");
        }
        return out;
    }

    /**
     * 按注册表声明的编码器处理待写入的值。
     *
     * <p>目前只有口令哈希一种。算法与 {@code UserServiceImpl} 一致：
     * {@code md5(PASSWORD_SALT + 明文)} 的 32 位小写十六进制；盐值取自
     * {@code UserConstant.PASSWORD_SALT}（单一来源），并已用库中现有账号的哈希反推验证。</p>
     *
     * <p>注意：{@code UserServiceImpl} 用的是 {@code String.getBytes()} 的平台默认字符集，
     * 这里保持一致以保证逐字节相同。非 ASCII 口令在不同默认字符集的平台上会得到不同哈希，
     * 这是既有实现的问题（本次未引入），建议后续统一改为显式 UTF-8。</p>
     */
    static Object encodeValue(String encoder, Object value) {
        if (value == null) {
            return null;
        }
        if (DataAdminRegistry.ENCODER_MD5_PASSWORD.equals(encoder)) {
            return DigestUtils.md5DigestAsHex((PASSWORD_SALT + value).getBytes());
        }
        throw new IllegalArgumentException("未知的写入编码器: " + encoder);
    }

    /**
     * 空字符串统一转为 null 还是保留？这里保留原值，仅把空白串规整为 null 以免污染数据。
     */
    static Object normalizeValue(Object v) {
        if (v instanceof String s) {
            String t = s.trim();
            return t.isEmpty() ? null : t;
        }
        return v;
    }

    private Object insertRow(DataAdminRegistry.EntityMeta m, Map<String, Object> values) {
        if (values.isEmpty()) {
            throw new IllegalArgumentException("没有可写入的列");
        }
        String cols = values.keySet().stream().map(DataAdminService::quote).collect(Collectors.joining(", "));
        String marks = values.keySet().stream().map(k -> "?").collect(Collectors.joining(", "));
        String sql = "INSERT INTO " + quote(m.getTable()) + " (" + cols + ") VALUES (" + marks + ")";

        KeyHolder kh = new GeneratedKeyHolder();
        jdbc().update(con -> {
            PreparedStatement ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            int i = 1;
            for (Object v : values.values()) {
                ps.setObject(i++, v);
            }
            return ps;
        }, kh);
        Number key = kh.getKey();
        return key == null ? null : key.longValue();
    }

    private int updateRow(DataAdminRegistry.EntityMeta m, Object id, Map<String, Object> values) {
        if (values.isEmpty()) {
            return 0;
        }
        String sets = values.keySet().stream().map(k -> quote(k) + " = ?").collect(Collectors.joining(", "));
        List<Object> args = new ArrayList<>(values.values());
        args.add(id);

        StringBuilder sql = new StringBuilder("UPDATE ").append(quote(m.getTable()))
                .append(" SET ").append(sets)
                .append(" WHERE ").append(quote(m.getIdColumn())).append(" = ?");
        if (m.isSoftDelete()) {
            sql.append(" AND isDelete = 0");
        }
        return jdbc().update(sql.toString(), args.toArray());
    }

    /**
     * 从明细中提取站点 ID 有序序列。未传明细时返回 null（表示不触碰派生列）。
     */
    static List<Long> extractStationIds(DataAdminRegistry.EntityMeta m, List<Map<String, Object>> details) {
        if (details == null) {
            return null;
        }
        List<Long> ids = new ArrayList<>();
        for (Map<String, Object> d : details) {
            Object sid = d.get("stationId");
            if (sid == null) {
                throw new IllegalArgumentException("明细缺少 stationId");
            }
            ids.add(Long.parseLong(String.valueOf(sid)));
        }
        return ids;
    }

    /**
     * 整体替换某主行下的明细集合（按 stationId 对齐：已有的更新、缺失的逻辑删除、新增的插入）。
     *
     * <p>同时维护主表 stationIds 派生列，保证「stationIds ↔ 明细行」一致。</p>
     */
    private void replaceChildren(DataAdminRegistry.EntityMeta parent,
                                 Object parentId,
                                 List<Map<String, Object>> details,
                                 List<Long> stationIds,
                                 boolean recomputeDirection) {
        if (details == null) {
            return;
        }
        DataAdminRegistry.EntityMeta child = registry.require(parent.getChildEntityKey());
        String fk = parent.getChildForeignKey();

        // 1. 既有子行：stationId -> id
        Map<Long, Object> existing = new LinkedHashMap<>();
        String selectSql = "SELECT " + quote(child.getIdColumn()) + ", stationId FROM "
                + quote(child.getTable()) + " WHERE " + quote(fk) + " = ? AND isDelete = 0";
        jdbc().query(selectSql, rs -> {
            existing.put(rs.getLong("stationId"), rs.getObject(child.getIdColumn()));
        }, parentId);

        // 2. 校验站点存在（防止产生悬空引用）
        validateStationsExist(stationIds);

        // 3. 逐条 upsert
        Set<Long> incoming = new LinkedHashSet<>(stationIds == null ? Collections.emptyList() : stationIds);
        for (Map<String, Object> d : details) {
            Long stationId = Long.parseLong(String.valueOf(d.get("stationId")));
            Map<String, Object> row = new LinkedHashMap<>();
            row.put(fk, parentId);
            for (Map.Entry<String, Object> e : d.entrySet()) {
                String col = e.getKey();
                if (col.equals(child.getIdColumn()) || col.equals(fk)) {
                    continue;
                }
                DataAdminRegistry.ColumnMeta c = child.column(col);
                if (c == null) {
                    throw new IllegalArgumentException("明细中存在未知列: " + col);
                }
                if (!child.isWritable(col)) {
                    throw new IllegalArgumentException("明细列不可写: " + col);
                }
                assertSafeIdentifier(col);
                row.put(col, normalizeValue(e.getValue()));
            }

            // 按业务规则重算 direction（可选）
            if (recomputeDirection && row.containsKey("direction")) {
                Object rawDir = row.get("direction");
                row.put("direction", routeDirectionService.process(
                        rawDir == null ? null : String.valueOf(rawDir), stationIds, stationId));
            }

            Object existingId = existing.remove(stationId);
            if (existingId != null) {
                updateRow(child, existingId, row);
            } else {
                insertRow(child, row);
            }
        }

        // 4. 本次未出现的既有子行 → 逻辑删除
        for (Object staleId : existing.values()) {
            if (child.isSoftDelete()) {
                jdbc().update("UPDATE " + quote(child.getTable()) + " SET isDelete = 1 WHERE "
                        + quote(child.getIdColumn()) + " = ?", staleId);
            } else {
                jdbc().update("DELETE FROM " + quote(child.getTable()) + " WHERE "
                        + quote(child.getIdColumn()) + " = ?", staleId);
            }
        }
    }

    /**
     * 校验站点 ID 均存在且未删除。
     */
    private void validateStationsExist(List<Long> stationIds) {
        if (stationIds == null || stationIds.isEmpty()) {
            return;
        }
        Set<Long> unique = new LinkedHashSet<>(stationIds);
        String marks = unique.stream().map(x -> "?").collect(Collectors.joining(", "));
        List<Long> found = jdbc().queryForList(
                "SELECT id FROM t_station WHERE isDelete = 0 AND id IN (" + marks + ")",
                Long.class, unique.toArray());
        Set<Long> foundSet = new LinkedHashSet<>(found);
        for (Long id : unique) {
            if (!foundSet.contains(id)) {
                throw new IllegalArgumentException("站点不存在或已删除: " + id);
            }
        }
    }

    // ==================== 内部：安全 ====================

    static void assertSafeIdentifier(String name) {
        if (name == null || !SAFE_IDENTIFIER.matcher(name).matches()) {
            throw new IllegalArgumentException("非法标识符: " + name);
        }
    }

    static String quote(String identifier) {
        assertSafeIdentifier(identifier);
        return "`" + identifier + "`";
    }

    private long clampPageSize(Long pageSize) {
        if (pageSize == null || pageSize < 1) {
            return 20L;
        }
        return Math.min(pageSize, MAX_PAGE_SIZE);
    }
}
