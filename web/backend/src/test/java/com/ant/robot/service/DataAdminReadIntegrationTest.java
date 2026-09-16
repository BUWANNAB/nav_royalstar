package com.ant.robot.service;

import com.ant.robot.config.DataAdminRegistry;
import com.ant.robot.model.request.DataAdminQueryRequest;
import com.ant.robot.model.vo.DataAdminPageVo;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.Connection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 数据管理通用服务对真实 MySQL 的<b>只读</b>集成测试。
 *
 * <p>目的：验证生成的 SQL（反引号引用、isDelete 条件、LIKE/等值筛选、LIMIT/OFFSET、
 * ORDER BY）能在真实库上正确执行——纯单测无法覆盖这一点。</p>
 *
 * <p><b>只做读操作</b>，不写任何数据。数据库不可达时整类跳过。
 * 连接参数可用环境变量覆盖：{@code ROBOT_DB_URL} / {@code ROBOT_DB_USERNAME} / {@code ROBOT_DB_PASSWORD}。</p>
 *
 * @author ChenWeihan
 */
class DataAdminReadIntegrationTest {

    private static final String DEFAULT_URL =
            "jdbc:mysql://127.0.0.1:3306/db_ant?serverTimezone=Asia/Shanghai";

    private static DataAdminService service;
    private static DataAdminRegistry registry;
    private static boolean available;

    @BeforeAll
    static void setUp() {
        String url = env("ROBOT_DB_URL", DEFAULT_URL);
        String user = env("ROBOT_DB_USERNAME", "root");
        String pass = env("ROBOT_DB_PASSWORD", "root");

        DriverManagerDataSource ds = new DriverManagerDataSource(url, user, pass);
        ds.setDriverClassName("com.mysql.cj.jdbc.Driver");

        try (Connection c = ds.getConnection()) {
            available = c.isValid(3);
        } catch (Exception e) {
            available = false;
        }

        registry = new DataAdminRegistry();
        registry.init();

        service = new DataAdminService();
        ReflectionTestUtils.setField(service, "registry", registry);
        ReflectionTestUtils.setField(service, "dataSource", ds);
    }

    private static String env(String key, String def) {
        String v = System.getenv(key);
        return v == null || v.isBlank() ? def : v;
    }

    private void requireDb() {
        Assumptions.assumeTrue(available, "MySQL 不可达，跳过只读集成测试");
    }

    // ==================== 列表分页 ====================

    @Test
    @DisplayName("站点分页查询能跑通，返回行数与元数据列一致")
    void listStation_page() {
        requireDb();
        DataAdminQueryRequest req = new DataAdminQueryRequest();
        req.setPageNum(1L);
        req.setPageSize(5L);

        DataAdminPageVo page = service.list("station", req);

        assertTrue(page.getTotal() > 0, "站点应非空");
        assertEquals(5, page.getRows().size());
        assertEquals(1L, page.getPageNum());
        assertEquals(5L, page.getPageSize());

        // 返回的键必须严格等于注册表中的列集合
        List<String> metaCols = registry.require("station").getColumns().stream()
                .map(DataAdminRegistry.ColumnMeta::getName).sorted().collect(Collectors.toList());
        assertEquals(metaCols, page.getRows().get(0).keySet().stream().sorted().collect(Collectors.toList()));
    }

    @Test
    @DisplayName("默认排除逻辑删除，includeDeleted 时总数变大")
    void listStation_includeDeleted() {
        requireDb();
        DataAdminQueryRequest normal = new DataAdminQueryRequest();
        normal.setPageSize(1L);
        long activeTotal = service.list("station", normal).getTotal();

        DataAdminQueryRequest withDeleted = new DataAdminQueryRequest();
        withDeleted.setPageSize(1L);
        withDeleted.setIncludeDeleted(true);
        long allTotal = service.list("station", withDeleted).getTotal();

        assertTrue(allTotal > activeTotal,
                "库里存在 isDelete=1 的站点，含删除查询总数应更大: " + activeTotal + " vs " + allTotal);
    }

    @Test
    @DisplayName("默认查询返回的行 isDelete 全为 0")
    void listStation_noDeletedRowsLeaked() {
        requireDb();
        DataAdminQueryRequest req = new DataAdminQueryRequest();
        req.setPageSize(50L);
        DataAdminPageVo page = service.list("route", req);

        for (Map<String, Object> row : page.getRows()) {
            Object v = row.get("isDelete");
            Number n = v == null ? 0 : (Number) v;
            assertEquals(0, n.intValue(), "默认查询不应返回已删除行: " + row.get("id"));
        }
    }

    @Test
    @DisplayName("分页偏移正确：第 2 页与第 1 页不重叠")
    void listStation_paginationOffset() {
        requireDb();
        DataAdminQueryRequest p1 = new DataAdminQueryRequest();
        p1.setPageNum(1L);
        p1.setPageSize(3L);
        DataAdminQueryRequest p2 = new DataAdminQueryRequest();
        p2.setPageNum(2L);
        p2.setPageSize(3L);

        List<Object> ids1 = service.list("station", p1).getRows().stream()
                .map(r -> r.get("id")).collect(Collectors.toList());
        List<Object> ids2 = service.list("station", p2).getRows().stream()
                .map(r -> r.get("id")).collect(Collectors.toList());

        assertEquals(3, ids1.size());
        assertEquals(3, ids2.size());
        assertTrue(ids1.stream().noneMatch(ids2::contains), "两页不应有重复行");
    }

    // ==================== 筛选与排序 ====================

    @Test
    @DisplayName("按主键等值筛选命中且仅命中一行")
    void listStation_filterByNumberColumn() {
        requireDb();
        DataAdminQueryRequest first = new DataAdminQueryRequest();
        first.setPageSize(1L);
        Object id = service.list("station", first).getRows().get(0).get("id");

        DataAdminQueryRequest req = new DataAdminQueryRequest();
        Map<String, String> filters = new LinkedHashMap<>();
        filters.put("id", String.valueOf(id));
        req.setFilters(filters);

        DataAdminPageVo page = service.list("station", req);
        assertEquals(1, page.getTotal());
        assertEquals(id, page.getRows().get(0).get("id"));
    }

    @Test
    @DisplayName("字符串列 LIKE 筛选能执行")
    void listStation_filterByStringColumn() {
        requireDb();
        DataAdminQueryRequest req = new DataAdminQueryRequest();
        Map<String, String> filters = new LinkedHashMap<>();
        // 用不可能命中的关键字，验证语句可执行且返回 0
        filters.put("stationName", "___no_such_station___");
        req.setFilters(filters);

        DataAdminPageVo page = service.list("station", req);
        assertEquals(0, page.getTotal());
    }

    @Test
    @DisplayName("排序生效（升序与降序结果相反）")
    void listStation_sort() {
        requireDb();
        DataAdminQueryRequest asc = new DataAdminQueryRequest();
        asc.setSortBy("id");
        asc.setSortOrder("asc");
        asc.setPageSize(10L);

        DataAdminQueryRequest desc = new DataAdminQueryRequest();
        desc.setSortBy("id");
        desc.setSortOrder("desc");
        desc.setPageSize(10L);

        List<Long> ascIds = service.list("station", asc).getRows().stream()
                .map(r -> ((Number) r.get("id")).longValue()).collect(Collectors.toList());
        List<Long> descIds = service.list("station", desc).getRows().stream()
                .map(r -> ((Number) r.get("id")).longValue()).collect(Collectors.toList());

        assertEquals(ascIds.stream().sorted(Comparator.naturalOrder()).collect(Collectors.toList()), ascIds);
        assertEquals(descIds.stream().sorted(Comparator.reverseOrder()).collect(Collectors.toList()), descIds);
    }

    // ==================== 详情与子行 ====================

    @Test
    @DisplayName("按主键取详情")
    void getById_station() {
        requireDb();
        DataAdminQueryRequest req = new DataAdminQueryRequest();
        req.setPageSize(1L);
        Object id = service.list("station", req).getRows().get(0).get("id");

        Map<String, Object> row = service.getById("station", id, false);
        assertNotNull(row);
        assertEquals(id, row.get("id"));
    }

    @Test
    @DisplayName("不存在的 id 返回 null 而不是抛异常")
    void getById_notFound() {
        requireDb();
        assertNull(service.getById("station", -1L, false));
    }

    @Test
    @DisplayName("非复合实体没有子行")
    void listChildren_emptyForSimpleEntity() {
        requireDb();
        assertTrue(service.listChildren("station", 1L, false).isEmpty());
    }

    @Test
    @DisplayName("路线子行（路线明细）能按 routeId 查出且非删除")
    void listChildren_routeDetails() {
        requireDb();
        // 在若干条路线中找一条确实带明细的
        DataAdminQueryRequest req = new DataAdminQueryRequest();
        req.setPageSize(50L);
        List<Map<String, Object>> routes = service.list("route", req).getRows();

        List<Map<String, Object>> found = null;
        Object foundRouteId = null;
        for (Map<String, Object> r : routes) {
            Object routeId = r.get("id");
            List<Map<String, Object>> children = service.listChildren("route", routeId, false);
            if (!children.isEmpty()) {
                found = children;
                foundRouteId = routeId;
                break;
            }
        }

        assertNotNull(found, "前 50 条路线中应至少有一条带明细");
        for (Map<String, Object> c : found) {
            assertEquals(foundRouteId, c.get("routeId"), "子行外键应等于主行 id");
            Number del = (Number) c.get("isDelete");
            assertEquals(0, del.intValue(), "子行默认不应含已删除");
        }
    }

    @Test
    @DisplayName("每页行数被钳制在上限内")
    void listStation_pageSizeClamped() {
        requireDb();
        DataAdminQueryRequest req = new DataAdminQueryRequest();
        req.setPageSize(100000L);
        DataAdminPageVo page = service.list("station", req);
        assertTrue(page.getPageSize() <= 200, "pageSize 应被钳制: " + page.getPageSize());
    }
}
