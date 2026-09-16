package com.ant.robot.controller;

import com.ant.robot.common.enums.ErrorCode;
import com.ant.robot.common.response.BaseResponse;
import com.ant.robot.common.response.ResultUtils;
import com.ant.robot.model.request.DataExportRequest;
import com.ant.robot.model.request.DataImportRequest;
import com.ant.robot.model.request.MkdirRequest;
import com.ant.robot.model.vo.DirListingVo;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import javax.sql.DataSource;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 数据下载：数据库整表导出 / 导入 + 工控机磁盘目录浏览。
 *
 * <p>由原独立「水质检测」项目的 serve.js（POST /api/localcommand）迁移而来，
 * 改为现场 Spring Boot 风格的 REST 接口。与原实现的差异见各类注释。</p>
 *
 * @author ChenWeihan
 */
@RestController
@RequestMapping("data-transfer")
@Slf4j
public class DataTransferController {

    // ============================ 配置 ============================

    @Value("${spring.datasource.url}")
    private String jdbcUrl;

    @Value("${spring.datasource.username:root}")
    private String dbUser;

    @Value("${spring.datasource.password:}")
    private String dbPassword;

    /**
     * mysqldump 可执行文件。Windows 环境需指到 .exe 全路径，例如
     * ROBOT_MYSQLDUMP_PATH=D:\mysql-8.0.27-winx64\bin\mysqldump.exe
     */
    @Value("${data-transfer.mysqldump-path:mysqldump}")
    private String mysqldumpPath;

    @Value("${data-transfer.mysql-path:mysql}")
    private String mysqlPath;

    /**
     * 允许浏览的根目录，逗号分隔；留空则允许浏览整个文件系统（沿用原 serve.js 行为）。
     * 现场生产建议显式收窄，例如 /home/lyagv,/media。
     */
    @Value("${data-transfer.browse-roots:}")
    private String browseRootsConfig;

    /**
     * 默认导出目录；留空则按 桌面 / Desktop / 用户主目录 依次探测。
     */
    @Value("${data-transfer.default-dir:}")
    private String defaultDirConfig;

    /**
     * 日志文件目录（数据下载页第三块面板）。
     */
    @Value("${data-transfer.log-dir:${user.dir}/logs}")
    private String logDirConfig;

    /**
     * 单次 mysqldump / mysql 执行的超时秒数。
     */
    @Value("${data-transfer.timeout-seconds:600}")
    private int timeoutSeconds;

    /**
     * 单次导入允许的最大字节数，默认 50MB。
     */
    @Value("${data-transfer.max-import-bytes:52428800}")
    private long maxImportBytes;

    /**
     * 导入前是否自动做整库带时间戳回滚备份（默认开启）。
     * 融合文档要求「执行前必须先将家里当前 db_ant 保留为带时间戳的回滚备份」。
     */
    @Value("${data-transfer.backup-before-import:true}")
    private boolean backupBeforeImport;

    @Resource
    private DataSource dataSource;

    // ============================ 常量 ============================

    /**
     * 导出对象白名单：只允许这些表被 dump，前端传入的表名不参与拼接。
     *
     * <p>注：routeSql / stationRouteSql 相比原水质检测实现额外包含 t_route_detail。
     * 现场路线明细单独存在该表，不含它导出的路线无法还原。</p>
     *
     * <p>包级可见以便单元测试断言白名单范围。</p>
     */
    static final Map<String, List<String>> EXPORT_TABLES;

    static {
        Map<String, List<String>> m = new LinkedHashMap<>();
        m.put("sensorSql", Collections.singletonList("t_sensor"));
        m.put("stationSql", Collections.singletonList("t_station"));
        m.put("routeSql", Arrays.asList("t_route", "t_route_detail"));
        m.put("stationRouteSql", Arrays.asList("t_station", "t_route", "t_route_detail"));
        EXPORT_TABLES = Collections.unmodifiableMap(m);
    }

    /**
     * 危险语句探测：命中则默认拒绝执行，需显式 allowDrop 才放行。
     * 包级可见以便单元测试直接覆盖。
     */
    static final Pattern DANGEROUS_SQL = Pattern.compile(
            "\\b(DROP\\s+(TABLE|DATABASE|SCHEMA)|TRUNCATE\\s+TABLE)\\b",
            Pattern.CASE_INSENSITIVE);

    /**
     * 统计 SQL 文本中 DROP / TRUNCATE 语句出现的次数。
     *
     * @param sql 待检测的 SQL 文本
     * @return 命中次数
     */
    static int countDangerousStatements(String sql) {
        if (sql == null || sql.isEmpty()) {
            return 0;
        }
        Matcher matcher = DANGEROUS_SQL.matcher(sql);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    private static final Pattern JDBC_URL_PATTERN = Pattern.compile(
            "jdbc:mysql://([^:/,]+)(?::(\\d+))?/([^?;]+)");

    // ============================ ① 导出 ============================

    /**
     * 导出数据。
     *
     * <p>mode=download 时把内容作为字符串返回，由前端触发浏览器下载；
     * mode=disk 时写入工控机磁盘并返回落盘绝对路径。与原 serve.js 语义一致。</p>
     */
    @PostMapping("export")
    public BaseResponse<String> export(@RequestBody DataExportRequest request) {
        String target = request == null ? null : request.getTarget();
        String mode = StringUtils.defaultIfBlank(request == null ? null : request.getMode(), "download");

        if (StringUtils.isBlank(target)) {
            return ResultUtils.error(ErrorCode.PARAMS_ERROR, "缺少导出对象 target");
        }

        try {
            String content;
            String extension;
            String namePrefix;

            if ("sensorCsv".equals(target)) {
                content = buildSensorCsv();
                extension = "csv";
                namePrefix = "sensor_data";
            } else {
                List<String> tables = EXPORT_TABLES.get(target);
                if (tables == null) {
                    return ResultUtils.error(ErrorCode.PARAMS_ERROR, "不支持的导出对象: " + target);
                }
                content = runMysqldump(tables);
                extension = "sql";
                namePrefix = "stationRouteSql".equals(target) ? "station_route" : target.replace("Sql", "");
            }

            if ("disk".equalsIgnoreCase(mode)) {
                File dir = resolveExportDir(request.getDir());
                String fileName = buildExportFileName(request.getFilename(), namePrefix, extension);
                File outFile = new File(dir, fileName);
                // 写出时不做 BOM、LF 换行，保持与 mysqldump 原始输出一致
                Files.write(outFile.toPath(), content.getBytes(StandardCharsets.UTF_8));
                log.info("数据导出落盘: {} ({} 字节)", outFile.getAbsolutePath(), outFile.length());
                return ResultUtils.success(outFile.getAbsolutePath());
            }

            log.info("数据导出下载: target={} 长度={}", target, content.length());
            return ResultUtils.success(content);

        } catch (Exception e) {
            log.error("数据导出失败: target={}", target, e);
            return ResultUtils.error(ErrorCode.SYSTEM_ERROR, "导出失败: " + e.getMessage());
        }
    }

    /**
     * 组装水质数据 CSV。
     *
     * <p>刻意逐字保留原 serve.js 的怪异格式（把中文标签写进单元格），
     * 以便与旧工具产出的文件逐字节一致；如需改成标准 CSV 需两边同步调整。</p>
     */
    private String buildSensorCsv() {
        String sql = "SELECT createTime, stationname, temp, ph, o2 FROM t_sensor ORDER BY createTime";
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql);

        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> row : rows) {
            sb.append(nullToEmpty(row.get("createTime"))).append(", 检测站号：,")
                    .append(nullToEmpty(row.get("stationname"))).append(",: 温度：,")
                    .append(nullToEmpty(row.get("temp"))).append(",: PH：,")
                    .append(nullToEmpty(row.get("ph"))).append(",: 溶解氧：,")
                    .append(nullToEmpty(row.get("o2"))).append(" \n");
        }
        return sb.toString();
    }

    private String nullToEmpty(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    /**
     * 调用 mysqldump 导出指定表。
     *
     * <p>参数与 serve.js 的 runMysqldump 保持一致（含 --add-drop-table），
     * 因此产出的 .sql 与旧工具可互换；口令改用 MYSQL_PWD 环境变量传递，
     * 避免出现在进程命令行里被 ps 看到。</p>
     */
    private String runMysqldump(List<String> tables) throws Exception {
        DbTarget db = parseJdbcUrl(jdbcUrl);

        List<String> cmd = new ArrayList<>();
        cmd.add(resolveExecutable(mysqldumpPath, "mysqldump"));
        cmd.add("--host=" + db.host);
        if (db.port != null) {
            cmd.add("--port=" + db.port);
        }
        cmd.add("--user=" + dbUser);
        cmd.add("--default-character-set=utf8mb4");
        cmd.add("--add-drop-table");
        cmd.add("--skip-triggers");
        cmd.add("--skip-routines");
        cmd.add("--skip-lock-tables");
        cmd.add("--no-create-db");
        cmd.add("--set-charset");
        cmd.add(db.database);
        cmd.addAll(tables);

        ExecResult result = execute(cmd, null, dbPassword);
        if (result.exitCode != 0) {
            throw new IllegalStateException("mysqldump 退出码 " + result.exitCode + ": " + truncate(result.stderr));
        }
        if (StringUtils.isBlank(result.stdout)) {
            throw new IllegalStateException("mysqldump 未产出内容: " + truncate(result.stderr));
        }
        return result.stdout;
    }

    // ============================ ② 导入 ============================

    /**
     * 导入 SQL。
     *
     * <p>相比原 serve.js 增加了三道护栏：
     * ① 大小上限；② DROP/TRUNCATE 默认拒绝（需 allowDrop 显式放行）；
     * ③ 导入前自动整库带时间戳备份。</p>
     */
    @PostMapping("import")
    public BaseResponse<String> importSql(@RequestBody DataImportRequest request) {
        if (request == null) {
            return ResultUtils.error(ErrorCode.PARAMS_ERROR, "缺少请求体");
        }
        String mode = StringUtils.defaultIfBlank(request.getMode(), "upload");
        boolean allowDrop = Boolean.TRUE.equals(request.getAllowDrop());

        try {
            String raw;
            String source;

            if ("disk".equalsIgnoreCase(mode)) {
                if (StringUtils.isBlank(request.getPath())) {
                    return ResultUtils.error(ErrorCode.PARAMS_ERROR, "缺少文件路径");
                }
                Path filePath = checkBrowseAllowed(Paths.get(request.getPath()));
                if (!Files.isRegularFile(filePath)) {
                    return ResultUtils.error(ErrorCode.PARAMS_ERROR, "文件不存在: " + filePath);
                }
                if (!filePath.getFileName().toString().toLowerCase().endsWith(".sql")) {
                    return ResultUtils.error(ErrorCode.PARAMS_ERROR, "仅支持 .sql 文件");
                }
                long size = Files.size(filePath);
                if (size > maxImportBytes) {
                    return ResultUtils.error(ErrorCode.PARAMS_ERROR,
                            "文件过大: " + size + " 字节，上限 " + maxImportBytes);
                }
                raw = new String(Files.readAllBytes(filePath), StandardCharsets.UTF_8);
                source = filePath.toString();
            } else {
                raw = request.getContent();
                source = "浏览器上传";
            }

            if (StringUtils.isBlank(raw)) {
                return ResultUtils.error(ErrorCode.PARAMS_ERROR, "SQL 内容为空");
            }
            long byteLength = raw.getBytes(StandardCharsets.UTF_8).length;
            if (byteLength > maxImportBytes) {
                return ResultUtils.error(ErrorCode.PARAMS_ERROR,
                        "内容过大: " + byteLength + " 字节，上限 " + maxImportBytes);
            }

            // 护栏②：危险语句拦截
            int dangerousCount = countDangerousStatements(raw);
            if (dangerousCount > 0 && !allowDrop) {
                log.warn("导入被拒绝，脚本包含 {} 处 DROP/TRUNCATE 语句，来源={}", dangerousCount, source);
                return ResultUtils.error(ErrorCode.PARAMS_ERROR,
                        "脚本包含 " + dangerousCount + " 处 DROP/TRUNCATE 语句，已拒绝执行。"
                                + "确认要覆盖现有表时，请勾选「允许覆盖现有表」后重试。");
            }

            // 护栏③：导入前整库回滚备份
            String backupPath = null;
            if (backupBeforeImport) {
                try {
                    backupPath = backupWholeDatabase();
                } catch (Exception e) {
                    log.error("导入前自动备份失败，已中止导入", e);
                    return ResultUtils.error(ErrorCode.SYSTEM_ERROR,
                            "导入前自动备份失败，已中止导入: " + e.getMessage());
                }
            }

            String prepared = prepareImportSql(raw);
            DbTarget db = parseJdbcUrl(jdbcUrl);

            List<String> cmd = new ArrayList<>();
            cmd.add(resolveExecutable(mysqlPath, "mysql"));
            cmd.add("--host=" + db.host);
            if (db.port != null) {
                cmd.add("--port=" + db.port);
            }
            cmd.add("--user=" + dbUser);
            cmd.add("--default-character-set=utf8mb4");
            cmd.add(db.database);

            ExecResult result = execute(cmd, prepared, dbPassword);
            if (result.exitCode != 0) {
                log.error("导入失败: exit={} stderr={}", result.exitCode, truncate(result.stderr));
                return ResultUtils.error(ErrorCode.SYSTEM_ERROR,
                        "SQL 导入失败（退出码 " + result.exitCode + "）: " + truncate(result.stderr));
            }

            StringBuilder message = new StringBuilder("导入成功");
            if (dangerousCount > 0) {
                message.append("（执行了 ").append(dangerousCount).append(" 处 DROP/TRUNCATE）");
            }
            if (backupPath != null) {
                message.append("；回滚备份: ").append(backupPath);
            }
            log.info("导入成功: 来源={} 备份={}", source, backupPath);
            return ResultUtils.success(message.toString());

        } catch (Exception e) {
            log.error("数据导入失败", e);
            return ResultUtils.error(ErrorCode.SYSTEM_ERROR, "导入失败: " + e.getMessage());
        }
    }

    /**
     * 整库备份到默认导出目录，返回落盘路径。
     */
    private String backupWholeDatabase() throws Exception {
        DbTarget db = parseJdbcUrl(jdbcUrl);

        List<String> cmd = new ArrayList<>();
        cmd.add(resolveExecutable(mysqldumpPath, "mysqldump"));
        cmd.add("--host=" + db.host);
        if (db.port != null) {
            cmd.add("--port=" + db.port);
        }
        cmd.add("--user=" + dbUser);
        cmd.add("--default-character-set=utf8mb4");
        cmd.add("--add-drop-table");
        cmd.add("--skip-triggers");
        cmd.add("--skip-routines");
        cmd.add("--skip-lock-tables");
        cmd.add("--no-create-db");
        cmd.add("--set-charset");
        cmd.add(db.database);

        ExecResult result = execute(cmd, null, dbPassword);
        if (result.exitCode != 0) {
            throw new IllegalStateException("mysqldump 退出码 " + result.exitCode + ": " + truncate(result.stderr));
        }

        File dir = resolveExportDir(null);
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        File outFile = new File(dir, db.database + "_回滚备份_" + stamp + ".sql");
        Files.write(outFile.toPath(), result.stdout.getBytes(StandardCharsets.UTF_8));
        log.info("导入前回滚备份完成: {} ({} 字节)", outFile.getAbsolutePath(), outFile.length());
        return outFile.getAbsolutePath();
    }

    /**
     * 等价 serve.js 的 prepareImportSql：去 BOM、去 DEFINER、补字符集与外键开关。
     *
     * <p>无状态纯函数，包级可见以便单元测试直接覆盖。</p>
     */
    static String prepareImportSql(String raw) {
        String sql = raw;
        if (!sql.isEmpty() && sql.charAt(0) == 0xFEFF) {
            sql = sql.substring(1);
        }
        sql = sql.replaceAll("(?i)DEFINER\\s*=\\s*`[^`]*`@`[^`]*`", "");
        StringBuilder sb = new StringBuilder();
        if (!Pattern.compile("SET NAMES", Pattern.CASE_INSENSITIVE).matcher(sql).find()) {
            sb.append("SET NAMES utf8mb4;\n");
        }
        if (!Pattern.compile("FOREIGN_KEY_CHECKS", Pattern.CASE_INSENSITIVE).matcher(sql).find()) {
            sb.append("SET FOREIGN_KEY_CHECKS=0;\nSET UNIQUE_CHECKS=0;\n");
        }
        sb.append(sql);
        return sb.toString();
    }

    // ============================ ③ 目录浏览 ============================

    /**
     * 列子目录。
     */
    @GetMapping("dirs")
    public BaseResponse<DirListingVo> listDirs(@RequestParam(required = false) String path) {
        try {
            Path dir = resolveBrowsePath(path);
            DirListingVo vo = new DirListingVo();
            vo.setPath(dir.toString());
            vo.setDirs(listSubDirectories(dir));
            vo.setFiles(Collections.emptyList());

            Path parent = dir.getParent();
            boolean isRoot = isBrowseRoot(dir);
            vo.setParent(isRoot || parent == null ? null : parent.toString());
            vo.setIsRoot(isRoot);
            return ResultUtils.success(vo);
        } catch (Exception e) {
            log.error("列目录失败: path={}", path, e);
            return ResultUtils.error(ErrorCode.PARAMS_ERROR, "列目录失败: " + e.getMessage());
        }
    }

    /**
     * 列当前目录下的 .sql 文件（仅导入模式使用）。
     */
    @GetMapping("files")
    public BaseResponse<DirListingVo> listFiles(@RequestParam(required = false) String path) {
        try {
            Path dir = resolveBrowsePath(path);
            DirListingVo vo = new DirListingVo();
            vo.setPath(dir.toString());
            vo.setDirs(Collections.emptyList());
            vo.setFiles(listSqlFiles(dir));

            Path parent = dir.getParent();
            boolean isRoot = isBrowseRoot(dir);
            vo.setParent(isRoot || parent == null ? null : parent.toString());
            vo.setIsRoot(isRoot);
            return ResultUtils.success(vo);
        } catch (Exception e) {
            log.error("列文件失败: path={}", path, e);
            return ResultUtils.error(ErrorCode.PARAMS_ERROR, "列文件失败: " + e.getMessage());
        }
    }

    /**
     * 新建文件夹。
     */
    @PostMapping("mkdir")
    public BaseResponse<String> mkdir(@RequestBody MkdirRequest request) {
        try {
            Path parent = resolveBrowsePath(request == null ? null : request.getPath());
            String name = request == null ? null : request.getName();
            if (StringUtils.isBlank(name)) {
                return ResultUtils.error(ErrorCode.PARAMS_ERROR, "请输入文件夹名");
            }
            // 只取末段并过滤非法字符，防止通过名称逃逸
            String safeName = sanitizeFolderName(name);
            if (safeName == null) {
                return ResultUtils.error(ErrorCode.PARAMS_ERROR, "文件夹名无效");
            }
            Path target = checkBrowseAllowed(parent.resolve(safeName)).normalize();
            Files.createDirectories(target);
            log.info("新建目录: {}", target);
            return ResultUtils.success(target.toString());
        } catch (Exception e) {
            log.error("新建目录失败", e);
            return ResultUtils.error(ErrorCode.SYSTEM_ERROR, "创建失败: " + e.getMessage());
        }
    }

    // ============================ ④ 日志文件 ============================

    /**
     * 列出日志目录下的可下载文件（名称 + 绝对路径 + 大小）。
     *
     * <p>原 serve.js 是列项目内 data/ 目录并通过前端 $.getJSON 下载；
     * 现场没有该 data/ 目录，改为列配置的日志目录（默认 ${user.dir}/logs）。</p>
     */
    @GetMapping("logs")
    public BaseResponse<List<Map<String, Object>>> listLogs() {
        try {
            Path dir = Paths.get(logDirConfig).toAbsolutePath().normalize();
            if (!Files.isDirectory(dir)) {
                return ResultUtils.success(Collections.emptyList());
            }
            List<Map<String, Object>> out = new ArrayList<>();
            try (java.util.stream.Stream<Path> stream = Files.list(dir)) {
                stream.filter(Files::isRegularFile)
                        .sorted()
                        .forEach(p -> {
                            Map<String, Object> item = new LinkedHashMap<>();
                            item.put("name", p.getFileName().toString());
                            item.put("path", p.toString());
                            try {
                                item.put("size", Files.size(p));
                            } catch (IOException ignored) {
                                item.put("size", 0L);
                            }
                            out.add(item);
                        });
            }
            return ResultUtils.success(out);
        } catch (Exception e) {
            log.error("列日志失败", e);
            return ResultUtils.error(ErrorCode.SYSTEM_ERROR, "获取日志列表失败: " + e.getMessage());
        }
    }

    /**
     * 下载任意允许浏览范围内的文件。
     *
     * <p>返回类型用全限定名：本类已 import jakarta.annotation.Resource（@Resource 注入），
     * 与 org.springframework.core.io.Resource 同名，无法同时单类型导入。</p>
     */
    @GetMapping("download")
    public ResponseEntity<org.springframework.core.io.Resource> download(@RequestParam String path) {
        try {
            Path file = checkBrowseAllowed(Paths.get(path));
            if (!Files.isRegularFile(file)) {
                return ResponseEntity.notFound().build();
            }
            org.springframework.core.io.Resource resource = new FileSystemResource(file);
            String encoded = java.net.URLEncoder.encode(
                    file.getFileName().toString(), StandardCharsets.UTF_8).replace("+", "%20");
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename*=UTF-8''" + encoded)
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .body(resource);
        } catch (Exception e) {
            log.error("下载失败: path={}", path, e);
            return ResponseEntity.badRequest().build();
        }
    }

    // ============================ 内部工具 ============================

    /**
     * 解析 JDBC 地址，取出 host / port / database。
     *
     * <p>无状态纯函数，包级可见以便单元测试直接覆盖。</p>
     *
     * @param url spring.datasource.url
     * @return 解析结果
     */
    static DbTarget parseJdbcUrl(String url) {
        if (StringUtils.isBlank(url)) {
            throw new IllegalStateException("未配置 spring.datasource.url");
        }
        Matcher m = JDBC_URL_PATTERN.matcher(url);
        if (!m.find()) {
            throw new IllegalStateException("无法解析 JDBC 地址: " + url);
        }
        DbTarget db = new DbTarget();
        db.host = m.group(1);
        db.port = m.group(2);
        db.database = m.group(3);
        return db;
    }

    /**
     * 解析可执行文件：绝对路径原样返回；Windows 下补 .exe 以便从 PATH 找到。
     */
    private String resolveExecutable(String configured, String fallback) {
        String value = StringUtils.defaultIfBlank(configured, fallback);
        if (new File(value).isAbsolute()) {
            return value;
        }
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        if (windows && !value.toLowerCase().endsWith(".exe")) {
            return value + ".exe";
        }
        return value;
    }

    /**
     * 执行外部命令，stdin 可选，返回退出码与 stdout/stderr。
     *
     * <p>不在命令行上传递口令：口令经子进程环境变量 MYSQL_PWD 下发，
     * 避免出现在 ps / 任务管理器的命令行里。</p>
     */
    private ExecResult execute(List<String> cmd, String stdin, String password) throws Exception {
        log.info("执行外部命令: {}", redact(cmd));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        if (StringUtils.isNotBlank(password)) {
            pb.environment().put("MYSQL_PWD", password);
        }

        Process process = pb.start();

        // stderr 必须并发消费，否则管道写满会死锁
        StringBuilder errBuffer = new StringBuilder();
        Thread errThread = new Thread(() -> {
            try (InputStream in = process.getErrorStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) {
                    synchronized (errBuffer) {
                        errBuffer.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                    }
                }
            } catch (IOException ignored) {
                // 进程结束时流关闭属正常
            }
        });
        errThread.setDaemon(true);
        errThread.start();

        if (stdin != null) {
            try (OutputStream out = process.getOutputStream()) {
                out.write(stdin.getBytes(StandardCharsets.UTF_8));
                out.flush();
            } catch (IOException e) {
                log.warn("写入子进程 stdin 失败（可能是子进程已退出）: {}", e.getMessage());
            }
        } else {
            process.getOutputStream().close();
        }

        StringBuilder outBuffer = new StringBuilder();
        try (InputStream in = process.getInputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                outBuffer.append(new String(buf, 0, n, StandardCharsets.UTF_8));
            }
        }

        boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IllegalStateException("命令执行超时（" + timeoutSeconds + " 秒）");
        }
        errThread.join(2000);

        ExecResult result = new ExecResult();
        result.exitCode = process.exitValue();
        result.stdout = outBuffer.toString();
        synchronized (errBuffer) {
            result.stderr = errBuffer.toString();
        }
        return result;
    }

    /**
     * 命令行日志脱敏（当前参数不含口令，保留以防后续改为 -p 传参）。
     */
    private String redact(List<String> cmd) {
        return String.join(" ", cmd).replaceAll("(?i)(-p)\\S+", "$1***");
    }

    /**
     * 解析并校验要浏览的目录。
     */
    private Path resolveBrowsePath(String path) {
        if (StringUtils.isBlank(path)) {
            return defaultBrowsePath();
        }
        Path p = Paths.get(path.trim()).toAbsolutePath().normalize();
        return checkBrowseAllowed(p);
    }

    /**
     * 校验路径落在允许浏览的根之内。
     *
     * <p>无状态纯函数：<code>roots</code> 为空表示不限制（沿用原 serve.js 行为）。</p>
     *
     * @param path  待校验路径
     * @param roots 允许的根目录列表，空列表表示不限制
     * @return 规范化后的路径
     * @throws IllegalArgumentException 路径越界
     */
    static Path checkBrowseAllowed(Path path, List<String> roots) {
        Path normalized = path.toAbsolutePath().normalize();
        if (roots == null || roots.isEmpty()) {
            return normalized;
        }
        for (String root : roots) {
            Path rootPath = Paths.get(root).toAbsolutePath().normalize();
            if (normalized.equals(rootPath) || normalized.startsWith(rootPath)) {
                return normalized;
            }
        }
        throw new IllegalArgumentException("路径不在允许浏览的范围内: " + normalized);
    }

    private Path checkBrowseAllowed(Path path) {
        return checkBrowseAllowed(path, configuredBrowseRoots());
    }

    private boolean isBrowseRoot(Path dir) {
        List<String> roots = configuredBrowseRoots();
        if (roots.isEmpty()) {
            return dir.getParent() == null;
        }
        for (String root : roots) {
            if (dir.equals(Paths.get(root).toAbsolutePath().normalize())) {
                return true;
            }
        }
        return dir.getParent() == null;
    }

    private List<String> configuredBrowseRoots() {
        return parseBrowseRoots(browseRootsConfig);
    }

    /**
     * 解析 browse-roots 配置：逗号分隔，忽略空白项。
     *
     * <p>无状态纯函数，包级可见以便单元测试直接覆盖。</p>
     */
    static List<String> parseBrowseRoots(String config) {
        if (StringUtils.isBlank(config)) {
            return Collections.emptyList();
        }
        List<String> roots = new ArrayList<>();
        for (String part : config.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                roots.add(trimmed);
            }
        }
        return roots;
    }

    /**
     * 清洗文件夹名：只取末段并替换非法字符，防止通过名称逃逸目录。
     *
     * <p>无状态纯函数，包级可见以便单元测试直接覆盖。</p>
     *
     * @param name 用户输入的文件夹名
     * @return 清洗后的名称；无效时返回 null
     */
    static String sanitizeFolderName(String name) {
        if (StringUtils.isBlank(name)) {
            return null;
        }
        String safeName = Paths.get(name.trim()).getFileName().toString()
                .replaceAll("[\\\\/:*?\"<>|]", "_");
        if (StringUtils.isBlank(safeName) || ".".equals(safeName) || "..".equals(safeName)) {
            return null;
        }
        return safeName;
    }

    /**
     * 默认浏览目录：优先配置项，其次 桌面 / Desktop，最后用户主目录。
     */
    private Path defaultBrowsePath() {
        File configured = exportDirCandidate();
        return configured.toPath().toAbsolutePath().normalize();
    }

    /**
     * 解析导出目录：显式传入 > 配置项 > 桌面 / Desktop > 用户主目录。
     */
    private File resolveExportDir(String dir) throws IOException {
        if (StringUtils.isNotBlank(dir)) {
            Path p = checkBrowseAllowed(Paths.get(dir.trim()));
            Files.createDirectories(p);
            return p.toFile();
        }
        File target = exportDirCandidate();
        Files.createDirectories(target.toPath());
        return target;
    }

    private File exportDirCandidate() {
        if (StringUtils.isNotBlank(defaultDirConfig)) {
            return new File(defaultDirConfig.trim());
        }
        String home = System.getProperty("user.home", ".");
        for (String name : new String[]{"桌面", "Desktop"}) {
            File candidate = new File(home, name);
            if (candidate.isDirectory()) {
                return candidate;
            }
        }
        return new File(home);
    }

    /**
     * 生成导出文件名：只保留文件名部分并过滤非法字符，防止路径穿越。
     *
     * <p>无状态纯函数，包级可见以便单元测试直接覆盖。</p>
     */
    static String buildExportFileName(String requested, String prefix, String extension) {
        String base = StringUtils.isNotBlank(requested)
                ? requested.trim()
                : prefix + "_" + new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date()) + "." + extension;
        // 只保留文件名部分，避免路径穿越
        base = Paths.get(base).getFileName().toString().replaceAll("[\\\\/:*?\"<>|]", "_");
        if (!base.toLowerCase().endsWith("." + extension)) {
            base = base + "." + extension;
        }
        return base;
    }

    private List<String> listSubDirectories(Path dir) throws IOException {
        List<String> dirs = new ArrayList<>();
        try (java.util.stream.Stream<Path> stream = Files.list(dir)) {
            stream.filter(Files::isDirectory)
                    .filter(p -> !p.getFileName().toString().startsWith("."))
                    .forEach(p -> dirs.add(p.getFileName().toString()));
        }
        Collections.sort(dirs);
        return dirs;
    }

    private List<String> listSqlFiles(Path dir) throws IOException {
        List<String> files = new ArrayList<>();
        try (java.util.stream.Stream<Path> stream = Files.list(dir)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> !p.getFileName().toString().startsWith("."))
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".sql"))
                    .forEach(p -> files.add(p.getFileName().toString()));
        }
        Collections.sort(files);
        return files;
    }

    private String truncate(String s) {
        if (s == null) {
            return "";
        }
        String trimmed = s.trim();
        return trimmed.length() > 800 ? trimmed.substring(0, 800) + "..." : trimmed;
    }

    /**
     * 数据库连接目标。包级可见以便单元测试断言解析结果。
     */
    static class DbTarget {
        String host;
        String port;
        String database;
    }

    /**
     * 外部命令执行结果。
     */
    private static class ExecResult {
        int exitCode;
        String stdout;
        String stderr;
    }
}
