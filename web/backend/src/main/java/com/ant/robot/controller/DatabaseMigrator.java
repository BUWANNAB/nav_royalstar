package com.ant.robot.controller;

import lombok.extern.slf4j.Slf4j;

import java.sql.*;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 数据库迁移工具完整实现
 * 功能特点：
 * 1. 基于时间戳的增量数据迁移
 * 2. 多表分级迁移策略
 * 3. 完善的错误处理和日志记录
 * 4. 线程安全设计
 */
@Slf4j
public class DatabaseMigrator {
    // 数据库连接配置（实际项目中建议从配置文件读取）
    private static final String LOCAL_DB_URL = "jdbc:mysql://localhost:3306/db_ant?useSSL=false&serverTimezone=UTC";
    private static final String LOCAL_DB_USER = "root";
    private static final String LOCAL_DB_PASSWORD = "root";

    private static final String CLOUD_DB_URL = "jdbc:mysql://47.105.58.240:3306/ant?useSSL=true&allowPublicKeyRetrieval=true&serverTimezone=UTC";
    private static final String CLOUD_DB_USER = "ant";
    private static final String CLOUD_DB_PASSWORD = "J7Nt73rFWr8y6ak3";

    // 迁移元数据表（存储在云端数据库）
    private static final String MIGRATION_METADATA_TABLE = "t_migration_metadata";

    /**
     * 迁移任务配置
     * 格式：表名 -> 迁移频率(分钟)
     */
    private static final MigrationTask[] MIGRATION_TASKS = {
            new MigrationTask("t_log", 60),         // 日志表，每小时同步
            new MigrationTask("t_task", 60),       // 任务表，每小时同步
            new MigrationTask("t_run_log", 60),    // 运行日志，每小时同步
            new MigrationTask("t_route", 1440),    // 路线表，每天同步
            new MigrationTask("t_route_detail", 1440), // 路线详情，每天同步
            new MigrationTask("t_param", 1440),    // 参数表，每天同步
            new MigrationTask("t_station", 1440)   // 站点表，每天同步
    };

    // 任务调度线程池
    private final ScheduledExecutorService scheduler =
            Executors.newScheduledThreadPool(MIGRATION_TASKS.length);

    /**
     * 启动所有迁移任务
     */
    public synchronized void startAllMigrations() {
        try {
            // 1. 加载JDBC驱动
            Class.forName("com.mysql.cj.jdbc.Driver");
            log.info("MySQL JDBC驱动加载成功");

            // 2. 初始化元数据表
            initializeMigrationMetadataTable();

            // 3. 启动所有定时任务
            for (MigrationTask task : MIGRATION_TASKS) {
                startScheduledTask(task);
            }

            log.info("已成功启动{}个数据库迁移任务", MIGRATION_TASKS.length);
        } catch (ClassNotFoundException e) {
            log.error("MySQL JDBC驱动加载失败", e);
            stopAllMigrations();
        } catch (SQLException e) {
            log.error("迁移元数据表初始化失败", e);
            stopAllMigrations();
        }
    }

    /**
     * 初始化元数据表（如果不存在）
     */
    private void initializeMigrationMetadataTable() throws SQLException {
        try (Connection conn = createCloudConnection()) {
            String ddl = "CREATE TABLE IF NOT EXISTS `" + MIGRATION_METADATA_TABLE + "` (" +
                    "`table_name` VARCHAR(100) NOT NULL PRIMARY KEY," +
                    "`last_migration_time` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP," +
                    "`rows_migrated` INT DEFAULT 0," +
                    "`last_successful_migration` TIMESTAMP NULL," +
                    "`last_error` TEXT NULL" +
                    ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4";

            try (Statement stmt = conn.createStatement()) {
                stmt.execute(ddl);
                log.debug("迁移元数据表已初始化");
            }
        }
    }

    /**
     * 启动单个定时迁移任务
     */
    private void startScheduledTask(MigrationTask task) {
        scheduler.scheduleAtFixedRate(
                () -> {
                    try {
                        executeMigration(task.tableName);
                    } catch (Exception e) {
                        log.error("表[{}]迁移任务执行异常", task.tableName, e);
                        recordMigrationError(task.tableName, e.getMessage());
                    }
                },
                task.initialDelayMinutes,
                task.frequencyMinutes,
                TimeUnit.MINUTES
        );

        log.info("已调度表[{}]迁移任务 - 初始延迟: {}分钟, 执行间隔: {}分钟",
                task.tableName, task.initialDelayMinutes, task.frequencyMinutes);
    }

    /**
     * 执行单表数据迁移
     */
    public void executeMigration(String tableName) {
        long startTime = System.currentTimeMillis();
        log.info("开始同步表[{}]", tableName);

        try (Connection localConn = createLocalConnection();
             Connection cloudConn = createCloudConnection()) {

            // 1. 获取上次迁移时间
            Timestamp lastMigration = getLastMigrationTime(cloudConn, tableName);

            // 2. 禁用外键约束
            setForeignKeyChecks(cloudConn, false);

            // 3. 执行数据迁移
            int migratedRows = migrateIncrementalData(localConn, cloudConn, tableName, lastMigration);

            // 4. 更新迁移状态
            updateMigrationStatus(cloudConn, tableName, migratedRows, null);

            // 5. 恢复外键约束
            setForeignKeyChecks(cloudConn, true);

            log.info("表[{}]同步完成 - 迁移行数: {}, 耗时: {}ms",
                    tableName, migratedRows, System.currentTimeMillis() - startTime);

        } catch (SQLException e) {
            log.error("表[{}]同步失败", tableName, e);
            recordMigrationError(tableName, e.getMessage());
        }
    }

    /**
     * 增量数据迁移核心逻辑
     */
    private int migrateIncrementalData(Connection source, Connection target,
                                       String tableName, Timestamp lastMigration) throws SQLException {
        // 1. 构建增量查询SQL
        String whereClause = lastMigration != null ?
                " WHERE create_time > ? OR update_time > ?" : "";
        String selectSQL = "SELECT * FROM `" + tableName + "`" + whereClause;

        // 2. 准备批量插入语句
        String insertSQL = buildInsertStatement(tableName, source);

        try (PreparedStatement selectStmt = source.prepareStatement(selectSQL);
             PreparedStatement insertStmt = target.prepareStatement(insertSQL)) {

            // 设置时间参数
            if (lastMigration != null) {
                selectStmt.setTimestamp(1, lastMigration);
                selectStmt.setTimestamp(2, lastMigration);
            }

            // 3. 执行查询并处理结果
            ResultSet rs = selectStmt.executeQuery();
            ResultSetMetaData meta = rs.getMetaData();
            int columnCount = meta.getColumnCount();
            int batchCount = 0;
            int totalRows = 0;

            while (rs.next()) {
                // 设置插入参数
                for (int i = 1; i <= columnCount; i++) {
                    insertStmt.setObject(i, rs.getObject(i));
                }
                insertStmt.setObject(columnCount + 1, 1); // 设置device_id

                // 批量提交
                insertStmt.addBatch();
                if (++batchCount >= 500) {
                    int[] counts = insertStmt.executeBatch();
                    totalRows += counts.length;
                    batchCount = 0;
                }
            }

            // 提交剩余批次
            if (batchCount > 0) {
                int[] counts = insertStmt.executeBatch();
                totalRows += counts.length;
            }

            return totalRows;
        }
    }

    /**
     * 手动触发多个表的迁移
     * @param tableNames 需要迁移的表名数组
     */
    public void manualMigrateTables(String... tableNames) {
        if (tableNames == null || tableNames.length == 0) {
            System.out.println("⚠️ 未指定需要迁移的表名");
            return;
        }

        System.out.printf("\n🔄 开始手动迁移 %d 个表...\n", tableNames.length);

        for (String tableName : tableNames) {
            try {
                System.out.printf("⏳ 正在手动迁移表【%s】...\n", tableName);
                executeMigration(tableName);
            } catch (Exception e) {
                System.err.printf("❌ 表【%s】手动迁移失败: %s\n", tableName, e.getMessage());
            }
        }

        System.out.println("✅ 手动迁移任务完成");
    }

    /**
     * 构建INSERT语句（自动包含device_id字段）
     */
    private String buildInsertStatement(String tableName, Connection conn) throws SQLException {
        StringBuilder columns = new StringBuilder();
        StringBuilder values = new StringBuilder();

        try (ResultSet rs = conn.getMetaData().getColumns(null, null, tableName, null)) {
            while (rs.next()) {
                if (columns.length() > 0) {
                    columns.append(", ");
                    values.append(", ");
                }
                String colName = rs.getString("COLUMN_NAME");
                columns.append("`").append(colName).append("`");
                values.append("?");
            }
        }

        // 添加额外字段
        columns.append(", `device_id`");
        values.append(", ?");

        return String.format("INSERT INTO `%s` (%s) VALUES (%s)",
                tableName, columns, values);
    }

    /**
     * 获取表的上次迁移时间
     */
    private Timestamp getLastMigrationTime(Connection conn, String tableName) throws SQLException {
        String sql = "SELECT last_migration_time FROM " + MIGRATION_METADATA_TABLE +
                " WHERE table_name = ?";

        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, tableName);
            ResultSet rs = pstmt.executeQuery();
            return rs.next() ? rs.getTimestamp("last_migration_time") : null;
        }
    }

    /**
     * 更新迁移状态
     */
    private void updateMigrationStatus(Connection conn, String tableName,
                                       int rowsMigrated, String error) throws SQLException {
        String sql = "INSERT INTO " + MIGRATION_METADATA_TABLE +
                " (table_name, last_migration_time, rows_migrated, " +
                "last_successful_migration, last_error) " +
                "VALUES (?, CURRENT_TIMESTAMP, ?, ?, ?) " +
                "ON DUPLICATE KEY UPDATE " +
                "last_migration_time = VALUES(last_migration_time), " +
                "rows_migrated = VALUES(rows_migrated), " +
                "last_successful_migration = VALUES(last_successful_migration), " +
                "last_error = VALUES(last_error)";

        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, tableName);
            pstmt.setInt(2, rowsMigrated);
            pstmt.setTimestamp(3, error == null ? new Timestamp(System.currentTimeMillis()) : null);
            pstmt.setString(4, error);
            pstmt.executeUpdate();
        }
    }

    /**
     * 记录迁移错误
     */
    private void recordMigrationError(String tableName, String error) {
        try (Connection conn = createCloudConnection()) {
            updateMigrationStatus(conn, tableName, 0, error);
        } catch (SQLException e) {
            log.error("无法记录表[{}]的迁移错误", tableName, e);
        }
    }

    /**
     * 手动触发指定表迁移
     */
    public void manualMigrate(String... tableNames) {
        if (tableNames == null || tableNames.length == 0) {
            log.warn("未指定需要手动迁移的表");
            return;
        }

        log.info("开始手动迁移{}张表", tableNames.length);
        for (String tableName : tableNames) {
            try {
                executeMigration(tableName);
            } catch (Exception e) {
                log.error("表[{}]手动迁移失败", tableName, e);
            }
        }
    }

    /**
     * 停止所有迁移任务
     */
    public synchronized void stopAllMigrations() {
        log.info("正在停止数据库迁移服务...");
        try {
            if (!scheduler.isShutdown()) {
                scheduler.shutdown();
                if (scheduler.awaitTermination(30, TimeUnit.SECONDS)) {
                    log.info("所有迁移任务已安全停止");
                } else {
                    log.warn("部分任务仍在运行，将强制关闭");
                    scheduler.shutdownNow();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("迁移服务停止过程被中断", e);
        }
    }

    // ================ 数据库连接方法 ================

    private Connection createLocalConnection() throws SQLException {
        Properties props = new Properties();
        props.setProperty("user", LOCAL_DB_USER);
        props.setProperty("password", LOCAL_DB_PASSWORD);
        props.setProperty("connectTimeout", "5000");
        return DriverManager.getConnection(LOCAL_DB_URL, props);
    }

    private Connection createCloudConnection() throws SQLException {
        Properties props = new Properties();
        props.setProperty("user", CLOUD_DB_USER);
        props.setProperty("password", CLOUD_DB_PASSWORD);
        props.setProperty("connectTimeout", "10000");
        return DriverManager.getConnection(CLOUD_DB_URL, props);
    }

    private void setForeignKeyChecks(Connection conn, boolean enable) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("SET FOREIGN_KEY_CHECKS=" + (enable ? "1" : "0"));
        }
    }

    // ================ 内部配置类 ================

    private static class MigrationTask {
        final String tableName;
        final int frequencyMinutes;
        final int initialDelayMinutes;

        MigrationTask(String name, int freq) {
            this(name, freq, freq);
        }

        MigrationTask(String name, int freq, int delay) {
            this.tableName = name;
            this.frequencyMinutes = freq;
            this.initialDelayMinutes = delay;
        }
    }
}