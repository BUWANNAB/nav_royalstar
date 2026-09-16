package com.ant.robot.service;

import com.ant.robot.config.DataAdminRegistry;
import com.ant.robot.model.request.DataAdminQueryRequest;
import com.ant.robot.model.request.DataAdminWriteRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 数据管理通用服务的白名单与注入防护测试。
 *
 * <p>覆盖的是安全关键路径：标识符校验、列白名单、SQL 片段生成。
 * 不加载 Spring 上下文（ROS2 原生库缺失），也不连数据库。</p>
 *
 * @author ChenWeihan
 */
class DataAdminServiceSafetyTest {

    private DataAdminRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new DataAdminRegistry();
        registry.init();
    }

    // ==================== 标识符白名单 ====================

    @Test
    @DisplayName("合法标识符通过并加反引号")
    void quote_valid() {
        assertEquals("`id`", DataAdminService.quote("id"));
        assertEquals("`map_name`", DataAdminService.quote("map_name"));
        assertEquals("`_x1`", DataAdminService.quote("_x1"));
    }

    @Test
    @DisplayName("SQL 注入型标识符一律拒绝")
    void quote_rejectsInjection() {
        String[] bad = {
                null, "",
                "id; DROP TABLE t_station",
                "id`",
                "`id`",
                "id name",
                "1abc",
                "id--",
                "id/*x*/",
                "t_station` WHERE 1=1 -- ",
                "id) OR (1=1",
                "id\n",
                "站"
        };
        for (String s : bad) {
            assertThrows(IllegalArgumentException.class,
                    () -> DataAdminService.quote(s), "应拒绝: " + s);
            assertThrows(IllegalArgumentException.class,
                    () -> DataAdminService.assertSafeIdentifier(s), "应拒绝: " + s);
        }
    }

    // ==================== 列白名单与部分更新 ====================

    @Test
    @DisplayName("未知列被拒绝")
    void sanitize_unknownColumnRejected() {
        DataAdminWriteRequest req = write("password", "x");
        assertThrows(IllegalArgumentException.class,
                () -> DataAdminService.sanitizeValues(registry.require("station"), req, false));
    }

    @Test
    @DisplayName("不可写列被拒绝（id / createTime / isDelete）")
    void sanitize_readOnlyColumnRejected() {
        for (String col : List.of("id", "createTime", "updateTime", "isDelete")) {
            DataAdminWriteRequest req = write(col, "1");
            assertThrows(IllegalArgumentException.class,
                    () -> DataAdminService.sanitizeValues(registry.require("station"), req, false),
                    col + " 应被拒绝");
        }
    }

    @Test
    @DisplayName("派生列 stationIds 被拒绝并给出明确原因")
    void sanitize_derivedColumnRejected() {
        DataAdminWriteRequest req = write("stationIds", "[1,2]");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> DataAdminService.sanitizeValues(registry.require("route"), req, false));
        assertTrue(e.getMessage().contains("明细"), "错误信息应说明该列由明细维护");
    }

    @Test
    @DisplayName("新增时缺必填列被拒绝")
    void sanitize_createMissingRequired() {
        DataAdminWriteRequest req = write("stationCode", "A1");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> DataAdminService.sanitizeValues(registry.require("station"), req, true));
        assertTrue(e.getMessage().contains("stationName"), "应指出缺失的必填列");
    }

    @Test
    @DisplayName("新增时必填列齐备则通过")
    void sanitize_createWithRequired() {
        DataAdminWriteRequest req = write("stationName", "1号站");
        Map<String, Object> out = DataAdminService.sanitizeValues(registry.require("station"), req, true);
        assertEquals("1号站", out.get("stationName"));
        assertEquals(1, out.size());
    }

    @Test
    @DisplayName("部分更新：只返回请求中出现的列，未出现的不参与 SET")
    void sanitize_partialUpdate() {
        DataAdminWriteRequest req = write("stationName", "改名后");
        Map<String, Object> out = DataAdminService.sanitizeValues(registry.require("station"), req, false);
        assertEquals(1, out.size());
        assertTrue(out.containsKey("stationName"));
        // direction_option 这类未被提交的列必须不出现在 SET 列表里，避免误覆盖
        assertFalse(out.containsKey("map_name"));
    }

    @Test
    @DisplayName("更新时既无列又无明细则拒绝")
    void sanitize_updateNothing() {
        assertThrows(IllegalArgumentException.class,
                () -> DataAdminService.sanitizeValues(registry.require("station"),
                        new DataAdminWriteRequest(), false));
    }

    @Test
    @DisplayName("空白字符串规整为 null，正常值去除首尾空格")
    void normalizeValue_behavior() {
        assertEquals("x", DataAdminService.normalizeValue("  x  "));
        assertNull(DataAdminService.normalizeValue("   "));
        assertNull(DataAdminService.normalizeValue(""));
        assertEquals(123, DataAdminService.normalizeValue(123));
        assertNull(DataAdminService.normalizeValue(null));
    }

    // ==================== WHERE 生成 ====================

    @Test
    @DisplayName("默认排除逻辑删除数据（与原 queryall 行为一致）")
    void buildWhere_excludesDeletedByDefault() {
        DataAdminService.Where w = DataAdminService.buildWhere(
                registry.require("station"), new DataAdminQueryRequest());
        assertTrue(w.sql.contains("isDelete = 0"), w.sql);
        assertTrue(w.args.isEmpty());
    }

    @Test
    @DisplayName("includeDeleted=true 时不加删除条件")
    void buildWhere_includeDeleted() {
        DataAdminQueryRequest req = new DataAdminQueryRequest();
        req.setIncludeDeleted(true);
        DataAdminService.Where w = DataAdminService.buildWhere(registry.require("station"), req);
        assertFalse(w.sql.contains("isDelete"), w.sql);
    }

    @Test
    @DisplayName("字符串列走 LIKE 且关键字参数化绑定")
    void buildWhere_stringFilterUsesLike() {
        DataAdminQueryRequest req = new DataAdminQueryRequest();
        req.setFilters(new LinkedHashMap<>(Map.of("stationName", "1号")));
        DataAdminService.Where w = DataAdminService.buildWhere(registry.require("station"), req);

        assertTrue(w.sql.contains("`stationName` LIKE ?"), w.sql);
        assertEquals(List.of("%1号%"), w.args);
        // 关键字绝不能出现在 SQL 文本里
        assertFalse(w.sql.contains("1号"), "关键字不应拼接进 SQL");
    }

    @Test
    @DisplayName("非字符串列走等值匹配")
    void buildWhere_numberFilterUsesEquals() {
        DataAdminQueryRequest req = new DataAdminQueryRequest();
        req.setFilters(new LinkedHashMap<>(Map.of("id", "42")));
        DataAdminService.Where w = DataAdminService.buildWhere(registry.require("station"), req);
        assertTrue(w.sql.contains("`id` = ?"), w.sql);
        assertEquals(List.of("42"), w.args);
    }

    @Test
    @DisplayName("空白筛选值被跳过")
    void buildWhere_blankFilterSkipped() {
        DataAdminQueryRequest req = new DataAdminQueryRequest();
        req.setFilters(new LinkedHashMap<>(Map.of("stationName", "   ")));
        DataAdminService.Where w = DataAdminService.buildWhere(registry.require("station"), req);
        assertFalse(w.sql.contains("stationName"), w.sql);
    }

    @Test
    @DisplayName("筛选未知列或注入型列名被拒绝")
    void buildWhere_unknownFilterColumnRejected() {
        for (String col : List.of("password", "id; DROP TABLE t_station", "1=1")) {
            DataAdminQueryRequest req = new DataAdminQueryRequest();
            req.setFilters(new LinkedHashMap<>(Map.of(col, "x")));
            assertThrows(IllegalArgumentException.class,
                    () -> DataAdminService.buildWhere(registry.require("station"), req),
                    "应拒绝筛选列: " + col);
        }
    }

    // ==================== ORDER BY 生成 ====================

    @Test
    @DisplayName("未指定排序时按主键倒序，保证分页稳定")
    void buildOrder_default() {
        String sql = DataAdminService.buildOrder(registry.require("station"), new DataAdminQueryRequest());
        assertEquals(" ORDER BY `id` DESC", sql);
    }

    @Test
    @DisplayName("指定排序字段与方向")
    void buildOrder_explicit() {
        DataAdminQueryRequest req = new DataAdminQueryRequest();
        req.setSortBy("stationName");
        req.setSortOrder("asc");
        assertEquals(" ORDER BY `stationName` ASC",
                DataAdminService.buildOrder(registry.require("station"), req));
    }

    @Test
    @DisplayName("排序方向默认倒序")
    void buildOrder_defaultDirection() {
        DataAdminQueryRequest req = new DataAdminQueryRequest();
        req.setSortBy("speed");
        assertEquals(" ORDER BY `speed` DESC",
                DataAdminService.buildOrder(registry.require("route"), req));
    }

    @Test
    @DisplayName("排序未知列或注入型列名被拒绝")
    void buildOrder_injectionRejected() {
        for (String col : List.of("password", "id; DROP TABLE t_station", "(SELECT 1)")) {
            DataAdminQueryRequest req = new DataAdminQueryRequest();
            req.setSortBy(col);
            assertThrows(IllegalArgumentException.class,
                    () -> DataAdminService.buildOrder(registry.require("station"), req),
                    "应拒绝排序列: " + col);
        }
    }

    // ==================== 派生列提取 ====================

    @Test
    @DisplayName("未传明细时返回 null（表示不触碰派生列）")
    void extractStationIds_nullWhenNoDetails() {
        assertNull(DataAdminService.extractStationIds(registry.require("route"), null));
    }

    @Test
    @DisplayName("按明细顺序提取站点 ID")
    void extractStationIds_ordered() {
        List<Map<String, Object>> details = Arrays.asList(
                Map.of("stationId", 8165),
                Map.of("stationId", "8722"),
                Map.of("stationId", 8162L));
        assertEquals(List.of(8165L, 8722L, 8162L),
                DataAdminService.extractStationIds(registry.require("route"), details));
    }

    @Test
    @DisplayName("明细缺 stationId 时拒绝")
    void extractStationIds_missingStationId() {
        List<Map<String, Object>> details = Collections.singletonList(
                new LinkedHashMap<>(Map.of("speed", "0.2")));
        assertThrows(IllegalArgumentException.class,
                () -> DataAdminService.extractStationIds(registry.require("route"), details));
    }

    @Test
    @DisplayName("空明细返回空列表（表示清空该路线所有站点）")
    void extractStationIds_emptyDetails() {
        assertEquals(Collections.emptyList(),
                DataAdminService.extractStationIds(registry.require("route"), Collections.emptyList()));
    }

    // ==================== 辅助 ====================

    private DataAdminWriteRequest write(String column, Object value) {
        DataAdminWriteRequest req = new DataAdminWriteRequest();
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(column, value);
        req.setValues(values);
        return req;
    }
}
