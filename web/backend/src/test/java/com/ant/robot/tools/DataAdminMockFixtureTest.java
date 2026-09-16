package com.ant.robot.tools;

import com.alibaba.fastjson.JSON;
import com.ant.robot.config.DataAdminRegistry;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 前端 mock 快照（data-admin-mock.json）的一致性校验。
 *
 * <p>快照由 {@link DataAdminMockFixtureGenerator} 生成。这里只做校验，不写文件：
 * 命名以 {@code Test} 结尾，因此会在常规 {@code mvn test} 中被收集执行。</p>
 *
 * <p>校验点：</p>
 * <ol>
 *   <li>快照里的实体必须仍存在于注册表（防止实体改名/删除后快照变成孤儿）</li>
 *   <li>敏感实体（含明文密码列的 user）绝不出现在快照的实体列表与行数据中</li>
 *   <li>快照的列集合必须与注册表元数据一致（防止加了列却忘了重新生成）</li>
 * </ol>
 *
 * @author ChenWeihan
 */
class DataAdminMockFixtureTest {

    private static final File FIXTURE =
            new File("../frontend/src/modules/data-admin/mock/data-admin-mock.json");

    private Map<String, Object> loadFixture() throws IOException {
        Assumptions.assumeTrue(FIXTURE.isFile(),
                "mock 快照不存在，跳过校验（生成方式见 DataAdminMockFixtureGenerator）");
        String text = new String(Files.readAllBytes(FIXTURE.toPath()), StandardCharsets.UTF_8);
        return JSON.parseObject(text);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> entitiesOf(Map<String, Object> root) {
        List<Map<String, Object>> list = (List<Map<String, Object>>) root.get("entities");
        assertNotNull(list, "快照应包含 entities");
        return list;
    }

    @Test
    @DisplayName("快照中的实体必须仍存在于注册表")
    void entitiesSubsetOfRegistry() throws IOException {
        Map<String, Object> root = loadFixture();
        DataAdminRegistry registry = new DataAdminRegistry();
        registry.init();

        for (Map<String, Object> e : entitiesOf(root)) {
            String key = String.valueOf(e.get("key"));
            assertTrue(registry.exists(key),
                    "快照实体 " + key + " 已不在注册表中，需重新生成快照");
        }
    }

    @Test
    @DisplayName("敏感实体绝不出现在快照中（实体列表与行数据都要查）")
    void noSensitiveEntities() throws IOException {
        Map<String, Object> root = loadFixture();

        for (Map<String, Object> e : entitiesOf(root)) {
            String key = String.valueOf(e.get("key"));
            assertFalse(DataAdminMockFixtureGenerator.EXCLUDED_ENTITIES.contains(key),
                    "敏感实体不应出现在快照实体列表: " + key);
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> rows = (Map<String, Object>) root.get("rows");
        assertNotNull(rows, "快照应包含 rows");
        for (String excluded : DataAdminMockFixtureGenerator.EXCLUDED_ENTITIES) {
            assertFalse(rows.containsKey(excluded), "快照行数据不应包含敏感实体: " + excluded);
        }

        // 连列名都不应出现，避免只删了行却漏了列定义
        String raw = new String(Files.readAllBytes(FIXTURE.toPath()), StandardCharsets.UTF_8);
        assertFalse(raw.contains("userPassword"), "快照中不应出现 userPassword");
        assertFalse(raw.contains("userAccount"), "快照中不应出现 userAccount");
    }

    @Test
    @DisplayName("快照的列集合与注册表一致（防止加列后忘记重新生成）")
    void columnsMatchRegistry() throws IOException {
        Map<String, Object> root = loadFixture();
        DataAdminRegistry registry = new DataAdminRegistry();
        registry.init();

        for (Map<String, Object> e : entitiesOf(root)) {
            String key = String.valueOf(e.get("key"));
            if (!registry.exists(key)) {
                continue;
            }
            DataAdminRegistry.EntityMeta live = registry.require(key);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> cols = (List<Map<String, Object>>) e.get("columns");
            assertNotNull(cols, key + " 应包含 columns");

            assertEquals(live.getColumns().size(), cols.size(),
                    key + " 的列数量与注册表不一致，需重新生成快照");

            for (Map<String, Object> c : cols) {
                String name = String.valueOf(c.get("name"));
                assertNotNull(live.column(name),
                        key + " 的列 " + name + " 不在注册表中，需重新生成快照");
            }
        }
    }

    @Test
    @DisplayName("快照带有生成时间与来源说明，便于识别为抽样而非实时数据")
    void fixtureHasProvenance() throws IOException {
        Map<String, Object> root = loadFixture();
        assertNotNull(root.get("generatedAt"), "快照应记录生成时间");
        assertNotNull(root.get("source"), "快照应记录数据来源");
        assertNotNull(root.get("note"), "快照应说明用途");
    }
}
