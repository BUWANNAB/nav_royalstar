package com.ant.robot.config;

import com.ant.robot.model.vo.DataAdminColumnVo;
import com.ant.robot.model.vo.DataAdminEntityVo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 数据管理实体注册表的白名单行为测试。
 *
 * <p>不加载 Spring 上下文：本项目依赖 ROS2 原生库，无 ROS2 环境下上下文起不来。</p>
 *
 * @author ChenWeihan
 */
class DataAdminRegistryTest {

    private DataAdminRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new DataAdminRegistry();
        registry.init();
    }

    // ==================== 实体键白名单 ====================

    @Test
    @DisplayName("已注册的实体可以取到，表名由服务端映射")
    void require_registeredEntities() {
        assertEquals("t_station", registry.require("station").getTable());
        assertEquals("t_route", registry.require("route").getTable());
        assertEquals("t_route_detail", registry.require("routeDetail").getTable());
    }

    @Test
    @DisplayName("真实表名不能当实体键用（前端无法直接指定表）")
    void require_tableNameIsNotAKey() {
        assertThrows(IllegalArgumentException.class, () -> registry.require("t_station"));
        assertThrows(IllegalArgumentException.class, () -> registry.require("t_route"));
    }

    @Test
    @DisplayName("未注册实体一律拒绝（真实表名、表名当键、未纳入的表）")
    void require_unregisteredRejected() {
        // 日志/订单/文件类数据量大且不需要在数据管理页增删改，有意未纳入
        assertThrows(IllegalArgumentException.class, () -> registry.require("log"));
        assertThrows(IllegalArgumentException.class, () -> registry.require("runLog"));
        assertThrows(IllegalArgumentException.class, () -> registry.require("order"));
        assertThrows(IllegalArgumentException.class, () -> registry.require("files"));
        // 表名与实体键必须区分开（user 已注册，但 t_user 这个表名不是键）
        assertThrows(IllegalArgumentException.class, () -> registry.require("t_user"));
        assertThrows(IllegalArgumentException.class, () -> registry.require(null));
        assertThrows(IllegalArgumentException.class, () -> registry.require(""));
        assertThrows(IllegalArgumentException.class, () -> registry.require("../etc/passwd"));
    }

    @Test
    @DisplayName("阶段一注册的 3 个实体仍在，总数为 9")
    void all_phaseOneEntities() {
        List<String> keys = registry.all().stream()
                .map(DataAdminRegistry.EntityMeta::getKey).collect(Collectors.toList());
        assertEquals(9, keys.size(), "实际: " + keys);
        assertTrue(keys.containsAll(List.of("station", "route", "routeDetail")),
                "阶段一实体应仍在注册表中");
    }

    // ==================== 列白名单 ====================

    @Test
    @DisplayName("主键与审计列不可写")
    void columns_auditColumnsNotWritable() {
        DataAdminRegistry.EntityMeta station = registry.require("station");
        for (String col : List.of("id", "createTime", "updateTime", "isDelete")) {
            assertFalse(station.isWritable(col), col + " 不应可写");
        }
    }

    @Test
    @DisplayName("业务列可写，且能识别数据库真实列名（map_name 带下划线）")
    void columns_businessColumnsWritable() {
        DataAdminRegistry.EntityMeta station = registry.require("station");
        for (String col : List.of("stationName", "stationCode", "positionX", "orientationW", "map_name")) {
            assertTrue(station.isWritable(col), col + " 应可写");
            assertNotNull(station.column(col), col + " 应在元数据中");
        }
    }

    @Test
    @DisplayName("派生列 stationIds 不可直接写入")
    void columns_derivedColumnNotWritable() {
        DataAdminRegistry.EntityMeta route = registry.require("route");
        assertTrue(route.getDerivedColumns().contains("stationIds"));
        assertFalse(route.isWritable("stationIds"), "stationIds 由明细自动维护，不应可写");
        assertNotNull(route.column("stationIds"), "仍应出现在元数据里以便展示");
    }

    @Test
    @DisplayName("不存在的列返回 null，不可写")
    void columns_unknownColumn() {
        DataAdminRegistry.EntityMeta station = registry.require("station");
        assertNull(station.column("password"));
        assertNull(station.column("id; DROP TABLE t_station"));
        assertFalse(station.isWritable("任意列"));
    }

    @Test
    @DisplayName("路线应声明为复合实体并指明子表外键")
    void route_isComposite() {
        DataAdminRegistry.EntityMeta route = registry.require("route");
        assertTrue(route.isComposite());
        assertEquals("routeDetail", route.getChildEntityKey());
        assertEquals("routeId", route.getChildForeignKey());
    }

    @Test
    @DisplayName("非复合实体不声明子表")
    void nonCompositeEntities() {
        assertFalse(registry.require("station").isComposite());
        assertFalse(registry.require("routeDetail").isComposite());
    }

    // ==================== 下发给前端的元数据 ====================

    @Test
    @DisplayName("toVo 会把派生列标记为不可编辑，并保留 options 与 hint")
    void toVo_flags() {
        DataAdminEntityVo routeVo = registry.toVo(registry.require("route"));
        Map<String, DataAdminColumnVo> byName = routeVo.getColumns().stream()
                .collect(Collectors.toMap(DataAdminColumnVo::getName, c -> c));

        assertFalse(byName.get("stationIds").isEditable(), "派生列不应可编辑");
        assertTrue(byName.get("routeName").isEditable());
        assertTrue(byName.get("routeName").isRequired());
        assertFalse(byName.get("id").isEditable());
        assertEquals(List.of("", "0", "1", "2"), byName.get("routesource").getOptions());
        assertNotNull(byName.get("stationIds").getHint());
        assertTrue(routeVo.isComposite());
        assertEquals("routeDetail", routeVo.getChildEntityKey());
    }

    @Test
    @DisplayName("direction 字段带有 360/-360 语义说明")
    void toVo_directionHint() {
        DataAdminEntityVo detailVo = registry.toVo(registry.require("routeDetail"));
        DataAdminColumnVo direction = detailVo.getColumns().stream()
                .filter(c -> "direction".equals(c.getName())).findFirst().orElseThrow();
        assertTrue(direction.isEditable());
        assertNotNull(direction.getHint());
        assertTrue(direction.getHint().contains("360"), "应说明 360/-360 的自动计算语义");
    }

    @Test
    @DisplayName("direction_option 被纳入元数据（家里库特有字段，不能被静默忽略）")
    void toVo_directionOptionPresent() {
        DataAdminEntityVo detailVo = registry.toVo(registry.require("routeDetail"));
        assertTrue(detailVo.getColumns().stream()
                .anyMatch(c -> "direction_option".equals(c.getName())));
    }

    @Test
    @DisplayName("allVos 返回全部实体的元数据")
    void allVos() {
        List<DataAdminEntityVo> vos = registry.allVos();
        assertEquals(9, vos.size());
        vos.forEach(v -> {
            assertNotNull(v.getKey());
            assertNotNull(v.getLabel());
            assertFalse(v.getColumns().isEmpty());
        });
    }
}
