package com.ant.robot.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DataTransferController 安全关键逻辑的单元测试。
 *
 * <p>刻意不加载 Spring 上下文：本项目依赖 ROS2 (rcljava) 原生库，
 * 在无 ROS2 环境的机器上上下文无法启动。这里只覆盖无状态的纯函数。</p>
 *
 * @author ChenWeihan
 */
class DataTransferControllerTest {

    // ==================== 危险语句探测 ====================

    @Test
    @DisplayName("空输入不计入危险语句")
    void countDangerousStatements_nullAndEmpty() {
        assertEquals(0, DataTransferController.countDangerousStatements(null));
        assertEquals(0, DataTransferController.countDangerousStatements(""));
    }

    @Test
    @DisplayName("普通增删改查不触发危险拦截")
    void countDangerousStatements_safeStatements() {
        String sql = "CREATE TABLE `t_x` (`id` int);\n"
                + "INSERT INTO `t_x` VALUES (1);\n"
                + "SELECT * FROM `t_x`;\n"
                + "UPDATE `t_x` SET `id`=2 WHERE `id`=1;\n"
                + "DELETE FROM `t_x` WHERE `id`=2;\n";
        assertEquals(0, DataTransferController.countDangerousStatements(sql));
    }

    @Test
    @DisplayName("DROP TABLE / TRUNCATE / DROP DATABASE 均被识别")
    void countDangerousStatements_detectsDangerous() {
        assertEquals(1, DataTransferController.countDangerousStatements("DROP TABLE IF EXISTS `t_x`;"));
        assertEquals(1, DataTransferController.countDangerousStatements("drop table `t_x`;"));
        assertEquals(1, DataTransferController.countDangerousStatements("TRUNCATE TABLE `t_x`;"));
        assertEquals(1, DataTransferController.countDangerousStatements("DROP DATABASE `db_ant`;"));
        assertEquals(1, DataTransferController.countDangerousStatements("DROP SCHEMA `db_ant`;"));
    }

    @Test
    @DisplayName("多次出现按次数统计")
    void countDangerousStatements_countsAll() {
        String sql = "DROP TABLE IF EXISTS `t_a`;\n"
                + "CREATE TABLE `t_a` (`id` int);\n"
                + "DROP TABLE IF EXISTS `t_b`;\n"
                + "CREATE TABLE `t_b` (`id` int);\n";
        assertEquals(2, DataTransferController.countDangerousStatements(sql));
    }

    @Test
    @DisplayName("真实 mysqldump 产物必被识别（导出即可回灌，故需显式放行）")
    void countDangerousStatements_realDump() {
        // 与 export 接口 --add-drop-table 产出一致
        String dump = "DROP TABLE IF EXISTS `t_sensor`;\n"
                + "CREATE TABLE `t_sensor` (`id` bigint NOT NULL AUTO_INCREMENT);\n"
                + "LOCK TABLES `t_sensor` WRITE;\n"
                + "INSERT INTO `t_sensor` VALUES (1);\n"
                + "UNLOCK TABLES;\n";
        assertTrue(DataTransferController.countDangerousStatements(dump) > 0);
    }

    @Test
    @DisplayName("不误伤列名或字符串里含 drop/truncate 的普通语句")
    void countDangerousStatements_noFalsePositiveOnColumnNames() {
        // 未带 TABLE/DATABASE/SCHEMA 关键字的 drop 一词不应命中
        assertEquals(0, DataTransferController.countDangerousStatements(
                "INSERT INTO `t_log` (`msg`) VALUES ('drop the ball');"));
    }

    // ==================== 导出白名单 ====================

    @Test
    @DisplayName("导出对象白名单范围正确")
    void exportTables_whitelist() {
        assertEquals(Collections.singletonList("t_sensor"),
                DataTransferController.EXPORT_TABLES.get("sensorSql"));
        assertEquals(Collections.singletonList("t_station"),
                DataTransferController.EXPORT_TABLES.get("stationSql"));

        // 现场路线明细单独存表，路线导出必须带上，否则无法还原
        List<String> route = DataTransferController.EXPORT_TABLES.get("routeSql");
        assertTrue(route.contains("t_route"));
        assertTrue(route.contains("t_route_detail"));

        List<String> both = DataTransferController.EXPORT_TABLES.get("stationRouteSql");
        assertTrue(both.containsAll(Arrays.asList("t_station", "t_route", "t_route_detail")));
    }

    @Test
    @DisplayName("白名单不含未授权表，sensorCsv 走独立分支")
    void exportTables_rejectsUnknown() {
        assertNull(DataTransferController.EXPORT_TABLES.get("allTables"));
        assertNull(DataTransferController.EXPORT_TABLES.get("t_user"));
        // sensorCsv 由 CSV 分支处理，不参与 dump 白名单
        assertNull(DataTransferController.EXPORT_TABLES.get("sensorCsv"));
        // 任何表名都不应能作为 target 直接传入
        assertNull(DataTransferController.EXPORT_TABLES.get("t_sensor"));
    }

    // ==================== JDBC 地址解析 ====================

    @Test
    @DisplayName("解析 JDBC 地址：带端口与查询参数")
    void parseJdbcUrl_withPortAndParams() {
        DataTransferController.DbTarget db = DataTransferController.parseJdbcUrl(
                "jdbc:mysql://localhost:3306/db_ant?serverTimezone=Asia/Shanghai");
        assertEquals("localhost", db.host);
        assertEquals("3306", db.port);
        assertEquals("db_ant", db.database);
    }

    @Test
    @DisplayName("解析 JDBC 地址：无端口")
    void parseJdbcUrl_withoutPort() {
        DataTransferController.DbTarget db = DataTransferController.parseJdbcUrl(
                "jdbc:mysql://10.0.0.5/db_ant");
        assertEquals("10.0.0.5", db.host);
        assertNull(db.port);
        assertEquals("db_ant", db.database);
    }

    @Test
    @DisplayName("JDBC 地址非法或为空时抛异常")
    void parseJdbcUrl_invalid() {
        assertThrows(IllegalStateException.class,
                () -> DataTransferController.parseJdbcUrl(null));
        assertThrows(IllegalStateException.class,
                () -> DataTransferController.parseJdbcUrl("  "));
        assertThrows(IllegalStateException.class,
                () -> DataTransferController.parseJdbcUrl("jdbc:postgresql://localhost/db"));
    }

    // ==================== 导入前置处理 ====================

    @Test
    @DisplayName("导入前置处理：去 BOM、去 DEFINER、补字符集与外键开关")
    void prepareImportSql_full() {
        String raw = "\uFEFFCREATE DEFINER=`root`@`localhost` TABLE `t_x` (`id` int);";
        String out = DataTransferController.prepareImportSql(raw);

        assertFalse(out.startsWith("\uFEFF"), "BOM 应被移除");
        assertFalse(out.contains("DEFINER"), "DEFINER 应被移除");
        assertTrue(out.contains("SET NAMES utf8mb4"), "应补 SET NAMES");
        assertTrue(out.contains("FOREIGN_KEY_CHECKS=0"), "应补外键开关");
        assertTrue(out.contains("CREATE ") && out.contains("TABLE `t_x`"), "原始语句应保留");
    }

    @Test
    @DisplayName("已含 SET NAMES / 外键开关时不重复追加")
    void prepareImportSql_noDuplicatePreamble() {
        String raw = "SET NAMES utf8mb4;\nSET FOREIGN_KEY_CHECKS=0;\nINSERT INTO `t_x` VALUES (1);";
        String out = DataTransferController.prepareImportSql(raw);

        int namesCount = out.split("SET NAMES", -1).length - 1;
        int fkCount = out.split("FOREIGN_KEY_CHECKS", -1).length - 1;
        assertEquals(1, namesCount, "SET NAMES 不应重复");
        assertEquals(1, fkCount, "FOREIGN_KEY_CHECKS 不应重复");
    }

    // ==================== 浏览根解析与越界防护 ====================

    @Test
    @DisplayName("browse-roots 解析：逗号分隔、去空白、忽略空项")
    void parseBrowseRoots_normal() {
        assertEquals(Arrays.asList("/home/lyagv", "/media"),
                DataTransferController.parseBrowseRoots(" /home/lyagv , /media "));
        assertEquals(Collections.emptyList(), DataTransferController.parseBrowseRoots(""));
        assertEquals(Collections.emptyList(), DataTransferController.parseBrowseRoots(null));
        assertEquals(Collections.emptyList(), DataTransferController.parseBrowseRoots(" , , "));
    }

    @Test
    @DisplayName("未配置根目录时不限制浏览（沿用旧 serve.js 行为）")
    void checkBrowseAllowed_unrestrictedWhenNoRoots() {
        Path p = Paths.get(System.getProperty("java.io.tmpdir"));
        assertEquals(p.toAbsolutePath().normalize(),
                DataTransferController.checkBrowseAllowed(p, Collections.emptyList()));
        assertEquals(p.toAbsolutePath().normalize(),
                DataTransferController.checkBrowseAllowed(p, null));
    }

    @Test
    @DisplayName("根目录内的路径放行")
    void checkBrowseAllowed_insideRoot() {
        String root = System.getProperty("java.io.tmpdir");
        Path inside = Paths.get(root, "backup", "a.sql");
        assertEquals(inside.toAbsolutePath().normalize(),
                DataTransferController.checkBrowseAllowed(inside, Collections.singletonList(root)));
    }

    @Test
    @DisplayName("根目录外的路径被拒绝")
    void checkBrowseAllowed_outsideRoot() {
        String root = System.getProperty("java.io.tmpdir");
        Path outside = Paths.get(root).getParent().resolve("elsewhere");
        assertThrows(IllegalArgumentException.class,
                () -> DataTransferController.checkBrowseAllowed(outside, Collections.singletonList(root)));
    }

    @Test
    @DisplayName("用 .. 逃逸根目录时被拒绝")
    void checkBrowseAllowed_traversalBlocked() {
        String root = System.getProperty("java.io.tmpdir");
        // tmpdir/../.. 规范化后已在根之外
        Path escape = Paths.get(root, "..", "..", "escape");
        assertThrows(IllegalArgumentException.class,
                () -> DataTransferController.checkBrowseAllowed(escape, Collections.singletonList(root)));
    }

    // ==================== 文件名与目录名清洗 ====================

    @Test
    @DisplayName("文件夹名清洗：只取末段，无法通过名称跳目录")
    void sanitizeFolderName_normal() {
        assertEquals("backup", DataTransferController.sanitizeFolderName("backup"));
        assertEquals("backup", DataTransferController.sanitizeFolderName("  backup  "));
        // 只取末段，防止通过名称跳目录
        assertEquals("evil", DataTransferController.sanitizeFolderName("../../evil"));
        assertEquals("b", DataTransferController.sanitizeFolderName("a/b"));
        // 含冒号时的结果依赖平台（Windows 视 a: 为盘符 → b；Linux → a_b），
        // 因此只断言安全性质：结果非空且不再含任何路径分隔符。
        String withColon = DataTransferController.sanitizeFolderName("a:b");
        assertNotNull(withColon);
        assertFalse(withColon.contains("/"), "结果不应含 /");
        assertFalse(withColon.contains("\\"), "结果不应含 \\");
    }

    @Test
    @DisplayName("文件夹名清洗：无效输入返回 null")
    void sanitizeFolderName_invalid() {
        assertNull(DataTransferController.sanitizeFolderName(null));
        assertNull(DataTransferController.sanitizeFolderName("   "));
        assertNull(DataTransferController.sanitizeFolderName("."));
    }

    @Test
    @DisplayName("导出文件名清洗：路径穿越被剥离，扩展名被补齐")
    void buildExportFileName_sanitize() {
        assertEquals("x.sql",
                DataTransferController.buildExportFileName("../../x.sql", "sensor", "sql"));
        // 多级路径只保留末段文件名
        assertEquals("c.sql",
                DataTransferController.buildExportFileName("a/b/c.sql", "sensor", "sql"));
        assertEquals("data.csv",
                DataTransferController.buildExportFileName("data", "sensor", "csv"));
    }

    @Test
    @DisplayName("导出文件名为空时按前缀+时间戳自动命名")
    void buildExportFileName_autoName() {
        String name = DataTransferController.buildExportFileName(null, "sensor_data", "sql");
        assertTrue(name.startsWith("sensor_data_"), "应带前缀: " + name);
        assertTrue(name.endsWith(".sql"), "应带扩展名: " + name);
    }
}
