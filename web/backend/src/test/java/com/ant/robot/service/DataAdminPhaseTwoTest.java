package com.ant.robot.service;

import com.ant.robot.common.constants.UserConstant;
import com.ant.robot.config.DataAdminRegistry;
import com.ant.robot.model.request.DataAdminWriteRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.util.DigestUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 第二批实体注册与口令字段处理的测试。
 *
 * @author ChenWeihan
 */
class DataAdminPhaseTwoTest {

    private DataAdminRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new DataAdminRegistry();
        registry.init();
    }

    // ==================== 实体注册 ====================

    @Test
    @DisplayName("两批共注册 9 个实体")
    void allEntitiesRegistered() {
        List<String> keys = registry.all().stream()
                .map(DataAdminRegistry.EntityMeta::getKey).collect(Collectors.toList());
        assertEquals(9, keys.size(), "实际: " + keys);
        assertTrue(keys.containsAll(List.of(
                "station", "route", "routeDetail",
                "mapFileMapping", "parameter", "sensor", "waterdepth", "task", "user")));
    }

    @Test
    @DisplayName("第二批实体的表名映射正确")
    void phaseTwoTables() {
        assertEquals("t_map_file_mapping", registry.require("mapFileMapping").getTable());
        assertEquals("t_parameter", registry.require("parameter").getTable());
        assertEquals("t_sensor", registry.require("sensor").getTable());
        assertEquals("t_d270_data", registry.require("waterdepth").getTable());
        assertEquals("t_task", registry.require("task").getTable());
        assertEquals("t_user", registry.require("user").getTable());
    }

    @Test
    @DisplayName("t_map_file_mapping 没有 isDelete 列，因此标记为物理删除")
    void mapFileMappingIsPhysicalDelete() {
        DataAdminRegistry.EntityMeta m = registry.require("mapFileMapping");
        assertFalse(m.isSoftDelete(), "该表无 isDelete 列，应为物理删除");
        assertFalse(m.isComposite());
        // 时间列是下划线命名，与其它表的驼峰不同
        assertNotNull(m.column("create_time"));
        assertNotNull(m.column("update_time"));
        assertTrue(m.isWritable("map_name"));
        assertTrue(m.isWritable("file_name"));
    }

    @Test
    @DisplayName("系统参数表必填列正确（param_id/name/type/category）")
    void parameterRequiredColumns() {
        DataAdminRegistry.EntityMeta m = registry.require("parameter");
        assertTrue(m.isSoftDelete());
        for (String col : List.of("param_id", "name", "type", "category")) {
            assertTrue(m.isWritable(col), col + " 应可写");
            assertTrue(m.column(col).isRequired(), col + " 应为必填");
        }
    }

    @Test
    @DisplayName("任务表关键列可写且带必要选项")
    void taskColumns() {
        DataAdminRegistry.EntityMeta m = registry.require("task");
        for (String col : List.of("routeId", "routeName", "status", "weekdays")) {
            assertTrue(m.isWritable(col), col + " 应可写");
        }
        assertEquals(List.of("0", "1"), m.column("singleExecution").getOptions());
        // 审计列仍不可写
        for (String col : List.of("id", "createTime", "updateTime", "isDelete")) {
            assertFalse(m.isWritable(col), col + " 不应可写");
        }
    }

    // ==================== 用户口令 ====================

    @Test
    @DisplayName("用户口令列：可写 + 脱敏 + 绑定哈希编码器")
    void userPasswordColumn() {
        DataAdminRegistry.EntityMeta user = registry.require("user");
        DataAdminRegistry.ColumnMeta pwd = user.column("userPassword");
        assertNotNull(pwd);
        assertTrue(pwd.isEditable(), "口令需要可写，否则新增用户无法设密码");
        assertTrue(pwd.isMasked(), "读取必须脱敏");
        assertEquals(DataAdminRegistry.ENCODER_MD5_PASSWORD, pwd.getWriteEncoder(),
                "必须绑定哈希编码器，否则明文会直接落库");
        assertNotNull(pwd.getHint());
        assertTrue(pwd.getHint().contains("留空"), "应说明留空表示不修改");
        assertTrue(user.column("userAccount").isRequired());
    }

    @Test
    @DisplayName("口令盐值保持为既有取值（变更会导致所有存量口令无法登录）")
    void passwordSaltRegressionGuard() {
        assertEquals("ant-robot", UserConstant.PASSWORD_SALT,
                "该盐值已用库中现有账号的哈希反推验证过，不能改；"
                        + "若确需更改必须同时迁移全部存量口令");
    }

    @Test
    @DisplayName("哈希编码器：32 位小写十六进制且与既有算法一致")
    void encoderFormat() {
        Object out = DataAdminService.encodeValue(
                DataAdminRegistry.ENCODER_MD5_PASSWORD, "abc123");
        assertNotNull(out);
        String hex = String.valueOf(out);
        assertEquals(32, hex.length(), "MD5 应为 32 位: " + hex);
        assertTrue(hex.matches("[0-9a-f]{32}"), "应为小写十六进制: " + hex);

        String expected = DigestUtils.md5DigestAsHex(
                (UserConstant.PASSWORD_SALT + "abc123").getBytes());
        assertEquals(expected, hex, "必须与 UserServiceImpl 的算法完全一致");
    }

    @Test
    @DisplayName("哈希编码器：确定性、不同口令不同结果、null 安全")
    void encoderBehavior() {
        String a1 = String.valueOf(DataAdminService.encodeValue(
                DataAdminRegistry.ENCODER_MD5_PASSWORD, "pw-a"));
        String a2 = String.valueOf(DataAdminService.encodeValue(
                DataAdminRegistry.ENCODER_MD5_PASSWORD, "pw-a"));
        String b = String.valueOf(DataAdminService.encodeValue(
                DataAdminRegistry.ENCODER_MD5_PASSWORD, "pw-b"));
        assertEquals(a1, a2, "同一口令应得到同一哈希");
        assertNotEquals(a1, b, "不同口令应得到不同哈希");
        assertNull(DataAdminService.encodeValue(DataAdminRegistry.ENCODER_MD5_PASSWORD, null));
    }

    @Test
    @DisplayName("未知编码器直接拒绝，不静默原样写入")
    void encoderUnknownRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> DataAdminService.encodeValue("noSuchEncoder", "x"));
    }

    // ==================== 口令写入语义 ====================

    @Test
    @DisplayName("口令留空时不参与更新（避免把密码置空）")
    void passwordBlankMeansUnchanged() {
        DataAdminWriteRequest req = new DataAdminWriteRequest();
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("userAccount", "someone");
        values.put("userPassword", "");
        req.setValues(values);

        Map<String, Object> out = DataAdminService.sanitizeValues(
                registry.require("user"), req, false);

        assertFalse(out.containsKey("userPassword"), "留空应跳过，不能写成 null");
        assertEquals("someone", out.get("userAccount"));
    }

    @Test
    @DisplayName("回传脱敏占位符时视为未修改（防止把 ****** 当成明文哈希）")
    void passwordMaskSentinelSkipped() {
        DataAdminWriteRequest req = new DataAdminWriteRequest();
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("userAccount", "someone");
        values.put("userPassword", DataAdminService.MASK_SENTINEL);
        req.setValues(values);

        Map<String, Object> out = DataAdminService.sanitizeValues(
                registry.require("user"), req, false);

        assertFalse(out.containsKey("userPassword"),
                "脱敏占位符必须被跳过；否则会把 ****** 哈希后写入，直接破坏口令");
        assertEquals("someone", out.get("userAccount"), "其它列应正常参与更新");
    }

    @Test
    @DisplayName("只提交脱敏占位符时按「无可更新列」拒绝，而不是把口令写坏")
    void passwordOnlySentinelRejected() {
        DataAdminWriteRequest req = new DataAdminWriteRequest();
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("userPassword", DataAdminService.MASK_SENTINEL);
        req.setValues(values);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> DataAdminService.sanitizeValues(registry.require("user"), req, false));
        assertTrue(e.getMessage().contains("没有需要更新的列"), e.getMessage());
    }

    @Test
    @DisplayName("填写真实口令时按哈希写入，绝不出现明文")
    void passwordRealValueIsHashed() {
        String plain = "Str0ngPassw0rd";
        DataAdminWriteRequest req = new DataAdminWriteRequest();
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("userPassword", plain);
        req.setValues(values);

        Map<String, Object> out = DataAdminService.sanitizeValues(
                registry.require("user"), req, false);

        Object stored = out.get("userPassword");
        assertNotNull(stored);
        assertNotEquals(plain, stored, "落库值不能是明文");
        assertEquals(32, String.valueOf(stored).length());
        assertFalse(String.valueOf(stored).contains(plain));
    }

    @Test
    @DisplayName("新增用户时口令是必填项之外的列，账号必填仍受校验")
    void createUserRequiresAccount() {
        DataAdminWriteRequest req = new DataAdminWriteRequest();
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("userPassword", "whatever");
        req.setValues(values);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> DataAdminService.sanitizeValues(registry.require("user"), req, true));
        assertTrue(e.getMessage().contains("userAccount"), e.getMessage());
    }
}
