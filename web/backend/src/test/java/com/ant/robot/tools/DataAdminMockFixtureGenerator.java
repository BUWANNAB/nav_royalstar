package com.ant.robot.tools;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.serializer.SerializerFeature;
import com.ant.robot.config.DataAdminRegistry;
import com.ant.robot.model.vo.DataAdminEntityVo;
import com.ant.robot.service.DataAdminService;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 生成前端 mock 数据快照（无后端时供界面联调用）。
 *
 * <p>默认<b>不执行</b>，只做一致性校验。需要重新生成时运行：</p>
 * <pre>
 * mvn test -Dmaven.test.skip=false -Dtest=DataAdminMockFixtureGenerator -DdataAdmin.dumpMock=true
 * </pre>
 *
 * <p><b>安全约束</b>：{@link #EXCLUDED_ENTITIES} 中的实体会被完全排除，
 * 绝不写入快照。{@code user} 含明文密码列（t_user.userPassword），属于敏感数据，
 * 因此永不进入版本库。</p>
 *
 * <p>快照是只读抽样，用于界面联调，<b>不是实时数据</b>；页面会显示「模拟数据」标记。</p>
 *
 * @author ChenWeihan
 */
@Slf4j
class DataAdminMockFixtureGenerator {

    /** 绝不写入快照的实体（含凭据等敏感信息） */
    static final Set<String> EXCLUDED_ENTITIES = new LinkedHashSet<>(
            Arrays.asList("user"));

    /** 每个实体抽样行数 */
    private static final int SAMPLE_ROWS = 60;

    /** 复合实体：抽样的主行条数，以及据此抓取的子行上限 */
    private static final int COMPOSITE_PARENT_SAMPLE = 30;
    private static final int COMPOSITE_CHILD_LIMIT = 1200;

    private static final String OUT_RELATIVE =
            "../frontend/src/modules/data-admin/mock/data-admin-mock.json";

    private static final String DEFAULT_URL =
            "jdbc:mysql://127.0.0.1:3306/db_ant?serverTimezone=Asia/Shanghai";

    @Test
    @DisplayName("生成 mock 快照（需显式开启 -DdataAdmin.dumpMock=true）")
    void dumpFixture() throws Exception {
        Assumptions.assumeTrue(dumpRequested(), "未开启 dumpMock，跳过生成");

        String url = env("ROBOT_DB_URL", DEFAULT_URL);
        String user = env("ROBOT_DB_USERNAME", "root");
        String pass = env("ROBOT_DB_PASSWORD", "root");

        DriverManagerDataSource ds = new DriverManagerDataSource(url, user, pass);
        ds.setDriverClassName("com.mysql.cj.jdbc.Driver");
        try (Connection c = ds.getConnection()) {
            assertTrue(c.isValid(3), "数据库不可达");
        }

        DataAdminRegistry registry = new DataAdminRegistry();
        registry.init();

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("generatedAt", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));
        root.put("source", "db_ant（只读抽样快照）");
        root.put("note", "仅用于后端未启动时的界面联调，不是实时数据；写入操作只在浏览器内存中模拟");
        root.put("excludedEntities", new ArrayList<>(EXCLUDED_ENTITIES));

        List<DataAdminEntityVo> entityVos = new ArrayList<>();
        Map<String, List<Map<String, Object>>> rowsByEntity = new LinkedHashMap<>();

        for (DataAdminRegistry.EntityMeta m : registry.all()) {
            if (EXCLUDED_ENTITIES.contains(m.getKey())) {
                log.warn("跳过敏感实体，不写入快照: {}", m.getKey());
                continue;
            }
            entityVos.add(registry.toVo(m));

            if (m.isComposite()) {
                // 先抽主行，再按主行 id 抓子行，保证明细能对得上
                List<Map<String, Object>> parents = selectRows(ds, m, COMPOSITE_PARENT_SAMPLE, null, null);
                rowsByEntity.put(m.getKey(), parents);

                List<Long> parentIds = parents.stream()
                        .map(r -> ((Number) r.get(m.getIdColumn())).longValue())
                        .collect(Collectors.toList());

                DataAdminRegistry.EntityMeta child = registry.require(m.getChildEntityKey());
                if (!parentIds.isEmpty()) {
                    rowsByEntity.put(child.getKey(),
                            selectRows(ds, child, COMPOSITE_CHILD_LIMIT, m.getChildForeignKey(), parentIds));
                } else {
                    rowsByEntity.put(child.getKey(), new ArrayList<>());
                }
            } else {
                rowsByEntity.put(m.getKey(), selectRows(ds, m, SAMPLE_ROWS, null, null));
            }
        }

        root.put("entities", entityVos);
        root.put("rows", rowsByEntity);

        String json = JSON.toJSONString(root, SerializerFeature.PrettyFormat,
                SerializerFeature.WriteMapNullValue, SerializerFeature.DisableCircularReferenceDetect);

        File out = new File(OUT_RELATIVE);
        Files.createDirectories(out.getParentFile().toPath());
        Files.write(out.toPath(), json.getBytes(StandardCharsets.UTF_8));

        log.info("mock 快照已生成: {} ({} 字节)", out.getAbsolutePath(), json.length());
        rowsByEntity.forEach((k, v) -> log.info("  {} -> {} 行", k, v.size()));

        assertTrue(out.isFile());
        assertTrue(rowsByEntity.size() > 0);
    }

    // ==================== 内部 ====================

    /**
     * 本地标识符引用（与 DataAdminService.quote 同规则）。
     * 后者是包级私有，本工具在另一个包，因此自带一份，仅用于生成快照。
     */
    private static String quote(String identifier) {
        if (identifier == null || !identifier.matches("^[A-Za-z_][A-Za-z0-9_]*$")) {
            throw new IllegalArgumentException("非法标识符: " + identifier);
        }
        return "`" + identifier + "`";
    }

    private List<Map<String, Object>> selectRows(DriverManagerDataSource ds,
                                                 DataAdminRegistry.EntityMeta m,
                                                 int limit,
                                                 String parentColumn,
                                                 List<Long> parentIds) {
        String cols = m.getColumns().stream()
                .map(c -> quote(c.getName()))
                .collect(Collectors.joining(", "));

        StringBuilder sql = new StringBuilder("SELECT ").append(cols)
                .append(" FROM ").append(quote(m.getTable()));

        List<Object> args = new ArrayList<>();
        List<String> conds = new ArrayList<>();
        if (m.isSoftDelete()) {
            conds.add("isDelete = 0");
        }
        if (parentColumn != null) {
            String marks = parentIds.stream().map(x -> "?").collect(Collectors.joining(", "));
            conds.add(quote(parentColumn) + " IN (" + marks + ")");
            args.addAll(parentIds);
        }
        if (!conds.isEmpty()) {
            sql.append(" WHERE ").append(String.join(" AND ", conds));
        }
        sql.append(" ORDER BY ").append(quote(m.getIdColumn()))
                .append(" DESC LIMIT ").append(limit);

        org.springframework.jdbc.core.JdbcTemplate jt = new org.springframework.jdbc.core.JdbcTemplate(ds);
        return jt.query(sql.toString(), (rs, rowNum) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            for (DataAdminRegistry.ColumnMeta c : m.getColumns()) {
                Object v = rs.getObject(c.getName());
                // 时间统一转字符串，便于 JSON 序列化且前端直接展示
                if (v instanceof java.sql.Timestamp ts) {
                    v = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(ts);
                } else if (v != null && !(v instanceof Number) && !(v instanceof Boolean)) {
                    v = String.valueOf(v);
                }
                row.put(c.getName(), v);
            }
            return row;
        }, args.toArray());
    }

    /**
     * 是否请求生成快照。支持环境变量与系统属性两种方式：
     * 环境变量更可靠（Surefire 的 fork JVM 不一定会继承 Maven CLI 的 -D 属性）。
     */
    private static boolean dumpRequested() {
        if (Boolean.getBoolean("dataAdmin.dumpMock")) {
            return true;
        }
        String env = System.getenv("DATA_ADMIN_DUMP_MOCK");
        return env != null && ("true".equalsIgnoreCase(env) || "1".equals(env));
    }
    private static String env(String key, String def) {
        String v = System.getenv(key);
        return v == null || v.isBlank() ? def : v;
    }
}
