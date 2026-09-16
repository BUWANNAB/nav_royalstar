package com.ant.robot.config;

import com.ant.robot.model.vo.DataAdminColumnVo;
import com.ant.robot.model.vo.DataAdminEntityVo;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据管理页的实体与列白名单注册表。
 *
 * <p>安全设计：前端只传实体键（如 station）与列名，真实表名、可写列、可排序列、
 * 可筛选列全部以本注册表为准。任何不在注册表中的表名/列名都会被拒绝，
 * 因此前端无法通过参数注入表名或列名。</p>
 *
 * <p>新增实体只需在 {@link #init()} 中登记一条元数据，前后端均无需改逻辑。</p>
 *
 * @author ChenWeihan
 */
@Component
@Slf4j
public class DataAdminRegistry {

    /** 列类型常量 */
    public static final String TYPE_STRING = "string";
    public static final String TYPE_NUMBER = "number";
    public static final String TYPE_DATETIME = "datetime";
    public static final String TYPE_BOOLEAN = "boolean";
    public static final String TYPE_SELECT = "select";
    public static final String TYPE_JSON = "json";

    /**
     * 写入编码器：口令哈希。算法与 {@code UserServiceImpl} 一致，
     * 即 {@code md5(PASSWORD_SALT + 明文)} 的 32 位小写十六进制。
     */
    public static final String ENCODER_MD5_PASSWORD = "md5PasswordSalt";

    /**
     * 列元数据。
     */
    @Data
    public static class ColumnMeta {
        /** 真实数据库列名 */
        private final String name;
        /** 显示名 */
        private final String label;
        /** 控件类型（options() 会把它提升为 select，故不能是 final） */
        private String type;
        /** 是否可写 */
        private final boolean editable;
        /** 是否必填 */
        private final boolean required;
        /** 是否可排序 */
        private boolean sortable = true;
        /** 是否可筛选 */
        private boolean filterable = true;
        /** select 可选值 */
        private List<String> options;
        /** 提示 */
        private String hint;
        /** 是否敏感字段 */
        private boolean masked;
        /** 写入前的编码器键；null 表示原样写入 */
        private String writeEncoder;

        ColumnMeta(String name, String label, String type, boolean editable, boolean required) {
            this.name = name;
            this.label = label;
            this.type = type;
            this.editable = editable;
            this.required = required;
        }

        ColumnMeta noSort() {
            this.sortable = false;
            return this;
        }

        ColumnMeta noFilter() {
            this.filterable = false;
            return this;
        }

        ColumnMeta options(String... values) {
            this.type = TYPE_SELECT;
            this.options = List.of(values);
            return this;
        }

        ColumnMeta hint(String text) {
            this.hint = text;
            return this;
        }

        ColumnMeta masked() {
            this.masked = true;
            return this;
        }

        /** 指定写入前的编码器（如口令哈希），避免明文直接落库 */
        ColumnMeta encoder(String key) {
            this.writeEncoder = key;
            return this;
        }
    }

    /**
     * 实体元数据。
     */
    @Data
    public static class EntityMeta {
        private final String key;
        private final String label;
        private final String table;
        private String idColumn = "id";
        /** 是否存在 isDelete 列 */
        private boolean softDelete = true;
        /** 是否复合实体 */
        private boolean composite;
        /** 子实体键 */
        private String childEntityKey;
        /** 子表中指向主表的列 */
        private String childForeignKey;
        /** 主表中由子表派生维护、不接受前端写入的列 */
        private final List<String> derivedColumns = new ArrayList<>();
        private final List<ColumnMeta> columns = new ArrayList<>();
        private final Map<String, ColumnMeta> byName = new LinkedHashMap<>();

        EntityMeta(String key, String label, String table) {
            this.key = key;
            this.label = label;
            this.table = table;
        }

        EntityMeta column(ColumnMeta c) {
            columns.add(c);
            byName.put(c.getName(), c);
            return this;
        }

        EntityMeta derive(String... names) {
            Collections.addAll(derivedColumns, names);
            return this;
        }

        public ColumnMeta column(String name) {
            return byName.get(name);
        }

        public boolean isWritable(String name) {
            ColumnMeta c = byName.get(name);
            return c != null && c.isEditable() && !derivedColumns.contains(name);
        }
    }

    private final Map<String, EntityMeta> entities = new LinkedHashMap<>();

    @PostConstruct
    public void init() {
        // 阶段一
        registerStation();
        registerRouteDetail();
        registerRoute();
        // 阶段二
        registerMapFileMapping();
        registerParameter();
        registerSensor();
        registerWaterdepth();
        registerTask();
        registerUser();
        log.info("数据管理实体注册完成，共 {} 个：{}", entities.size(), entities.keySet());
    }

    // ==================== 阶段一：站点 ====================

    private void registerStation() {
        EntityMeta m = new EntityMeta("station", "站点", "t_station");
        m.column(new ColumnMeta("id", "ID", TYPE_NUMBER, false, false));
        m.column(new ColumnMeta("stationName", "站点名称", TYPE_STRING, true, true));
        m.column(new ColumnMeta("stationCode", "站点编码", TYPE_STRING, true, false)
                .hint("业务编码，deleteNew 接口按此定位"));
        m.column(new ColumnMeta("longitude", "经度", TYPE_STRING, true, false));
        m.column(new ColumnMeta("latitude", "纬度", TYPE_STRING, true, false));
        m.column(new ColumnMeta("positionX", "X 坐标", TYPE_STRING, true, false));
        m.column(new ColumnMeta("positionY", "Y 坐标", TYPE_STRING, true, false));
        m.column(new ColumnMeta("positionZ", "Z 坐标", TYPE_STRING, true, false));
        m.column(new ColumnMeta("orientationX", "四元数 X", TYPE_STRING, true, false));
        m.column(new ColumnMeta("orientationY", "四元数 Y", TYPE_STRING, true, false));
        m.column(new ColumnMeta("orientationZ", "四元数 Z", TYPE_STRING, true, false));
        m.column(new ColumnMeta("orientationW", "四元数 W", TYPE_STRING, true, false));
        m.column(new ColumnMeta("map_name", "所属地图", TYPE_STRING, true, false)
                .hint("数据库实际列名为 map_name（下划线），非驼峰"));
        m.column(new ColumnMeta("createTime", "创建时间", TYPE_DATETIME, false, false));
        m.column(new ColumnMeta("updateTime", "更新时间", TYPE_DATETIME, false, false));
        m.column(new ColumnMeta("isDelete", "已删除", TYPE_BOOLEAN, false, false));
        entities.put(m.getKey(), m);
    }

    // ==================== 阶段一：路线明细 ====================

    private void registerRouteDetail() {
        EntityMeta m = new EntityMeta("routeDetail", "路线明细", "t_route_detail");
        m.column(new ColumnMeta("id", "ID", TYPE_NUMBER, false, false));
        m.column(new ColumnMeta("routeId", "路线 ID", TYPE_NUMBER, true, true));
        m.column(new ColumnMeta("stationId", "站点 ID", TYPE_NUMBER, true, true));
        m.column(new ColumnMeta("speed", "速度(m/s)", TYPE_STRING, true, false)
                .hint("负数表示倒车"));
        m.column(new ColumnMeta("area", "区域", TYPE_STRING, true, false));
        m.column(new ColumnMeta("action", "动作", TYPE_STRING, true, false));
        m.column(new ColumnMeta("direction", "目标航向角(度)", TYPE_STRING, true, false)
                .hint("360=自动取到下一点航向；-360=自动取到上一点航向；其他值按绝对值规整"));
        m.column(new ColumnMeta("direction_option", "航向模式", TYPE_STRING, true, false)
                .options("useAngle")
                .hint("家里库特有字段，现场代码未使用；通用写入为部分更新，不会误覆盖"));
        m.column(new ColumnMeta("position", "定位模式", TYPE_STRING, true, false));
        m.column(new ColumnMeta("stop", "停车", TYPE_STRING, true, false));
        m.column(new ColumnMeta("runmode", "运行模式", TYPE_STRING, true, false)
                .options("0", "1")
                .hint("0=追踪，1=自转"));
        m.column(new ColumnMeta("lanechange", "变道", TYPE_STRING, true, false));
        m.column(new ColumnMeta("stopTime", "作业时长(s)", TYPE_STRING, true, false));
        m.column(new ColumnMeta("createTime", "创建时间", TYPE_DATETIME, false, false));
        m.column(new ColumnMeta("updateTime", "更新时间", TYPE_DATETIME, false, false));
        m.column(new ColumnMeta("isDelete", "已删除", TYPE_BOOLEAN, false, false));
        entities.put(m.getKey(), m);
    }

    // ==================== 阶段一：路线（复合实体） ====================

    private void registerRoute() {
        EntityMeta m = new EntityMeta("route", "路线", "t_route");
        m.column(new ColumnMeta("id", "ID", TYPE_NUMBER, false, false));
        m.column(new ColumnMeta("routeName", "路线名称", TYPE_STRING, true, true));
        m.column(new ColumnMeta("mapName", "所属地图", TYPE_STRING, true, false));
        m.column(new ColumnMeta("routeGroup", "路线分组", TYPE_STRING, true, false));
        m.column(new ColumnMeta("speed", "默认速度(m/s)", TYPE_STRING, true, false));
        m.column(new ColumnMeta("mapCoverage", "覆盖地图", TYPE_STRING, true, false));
        m.column(new ColumnMeta("routesource", "路线来源", TYPE_STRING, true, false)
                .options("", "0", "1", "2")
                .hint("0/1/2 对应不同写入接口（add/addMap/addAuto/addMix），空表示未分类"));
        m.column(new ColumnMeta("stationIds", "站点 ID 序列", TYPE_JSON, false, false)
                .hint("由明细自动维护的 JSON 数组，不接受直接写入"));
        m.column(new ColumnMeta("routeDetail", "遗留字段", TYPE_STRING, false, false)
                .hint("数据库遗留列，Java 实体未映射，样本数据为空"));
        m.column(new ColumnMeta("createTime", "创建时间", TYPE_DATETIME, false, false));
        m.column(new ColumnMeta("updateTime", "更新时间", TYPE_DATETIME, false, false));
        m.column(new ColumnMeta("isDelete", "已删除", TYPE_BOOLEAN, false, false));

        m.setComposite(true);
        m.setChildEntityKey("routeDetail");
        m.setChildForeignKey("routeId");
        m.derive("stationIds");
        entities.put(m.getKey(), m);
    }

    // ==================== 阶段二：地图文件映射 ====================
    // 注意：该表没有 isDelete 列，删除为物理删除；时间列是下划线命名。

    private void registerMapFileMapping() {
        EntityMeta m = new EntityMeta("mapFileMapping", "地图映射", "t_map_file_mapping");
        m.setSoftDelete(false);
        m.column(new ColumnMeta("id", "ID", TYPE_NUMBER, false, false));
        m.column(new ColumnMeta("map_name", "地图名称", TYPE_STRING, true, true)
                .hint("唯一键，数据库为 UNIQUE 约束"));
        m.column(new ColumnMeta("file_name", "文件名", TYPE_STRING, true, true));
        m.column(new ColumnMeta("create_time", "创建时间", TYPE_DATETIME, false, false));
        m.column(new ColumnMeta("update_time", "更新时间", TYPE_DATETIME, false, false));
        entities.put(m.getKey(), m);
    }

    // ==================== 阶段二：系统参数 ====================
    // 注意：真正在用的是本表 t_parameter（66 行）；
    // t_param（44 列宽表）是遗留结构且为 0 行，现有 /param 接口操作的是后者。

    private void registerParameter() {
        EntityMeta m = new EntityMeta("parameter", "系统参数", "t_parameter");
        m.column(new ColumnMeta("id", "ID", TYPE_NUMBER, false, false));
        m.column(new ColumnMeta("param_id", "参数键", TYPE_STRING, true, true)
                .hint("唯一键，代码按此键读取参数"));
        m.column(new ColumnMeta("name", "参数名称", TYPE_STRING, true, true));
        m.column(new ColumnMeta("value", "参数值", TYPE_STRING, true, false));
        m.column(new ColumnMeta("placeholder", "占位提示", TYPE_STRING, true, false));
        m.column(new ColumnMeta("type", "控件类型", TYPE_STRING, true, true)
                .hint("如 text / number / switch 等，由前端表单渲染使用"));
        m.column(new ColumnMeta("category", "分类", TYPE_STRING, true, true));
        m.column(new ColumnMeta("visible", "是否显示", TYPE_SELECT, true, false).options("1", "0"));
        m.column(new ColumnMeta("isDelete", "已删除", TYPE_BOOLEAN, false, false));
        entities.put(m.getKey(), m);
    }

    // ==================== 阶段二：水质传感器数据 ====================

    private void registerSensor() {
        EntityMeta m = new EntityMeta("sensor", "水质传感器数据", "t_sensor");
        m.column(new ColumnMeta("id", "ID", TYPE_NUMBER, false, false));
        m.column(new ColumnMeta("stationname", "检测站号", TYPE_STRING, true, false));
        m.column(new ColumnMeta("temp", "温度", TYPE_STRING, true, false));
        m.column(new ColumnMeta("ph", "PH 值", TYPE_STRING, true, false));
        m.column(new ColumnMeta("o2", "溶解氧", TYPE_STRING, true, false));
        m.column(new ColumnMeta("createTime", "采集时间", TYPE_DATETIME, false, false));
        m.column(new ColumnMeta("updateTime", "更新时间", TYPE_DATETIME, false, false));
        m.column(new ColumnMeta("isDelete", "已删除", TYPE_BOOLEAN, false, false));
        entities.put(m.getKey(), m);
    }

    // ==================== 阶段二：水深数据 ====================

    private void registerWaterdepth() {
        EntityMeta m = new EntityMeta("waterdepth", "水深数据", "t_d270_data");
        m.column(new ColumnMeta("id", "ID", TYPE_NUMBER, false, false));
        m.column(new ColumnMeta("longitude", "经度", TYPE_STRING, true, false));
        m.column(new ColumnMeta("latitude", "纬度", TYPE_STRING, true, false));
        m.column(new ColumnMeta("altitude", "海拔", TYPE_STRING, true, false));
        m.column(new ColumnMeta("depth", "深度", TYPE_STRING, true, false)
                .hint("-1.000000 表示未检测到数据"));
        m.column(new ColumnMeta("createTime", "采集时间", TYPE_DATETIME, false, false));
        m.column(new ColumnMeta("updateTime", "更新时间", TYPE_DATETIME, false, false));
        m.column(new ColumnMeta("isDelete", "已删除", TYPE_BOOLEAN, false, false));
        entities.put(m.getKey(), m);
    }

    // ==================== 阶段二：任务 ====================

    private void registerTask() {
        EntityMeta m = new EntityMeta("task", "任务", "t_task");
        m.column(new ColumnMeta("id", "ID", TYPE_NUMBER, false, false));
        m.column(new ColumnMeta("description", "任务描述", TYPE_STRING, true, false));
        m.column(new ColumnMeta("routeId", "路线 ID", TYPE_STRING, true, false));
        m.column(new ColumnMeta("routeName", "路线名称", TYPE_STRING, true, false));
        m.column(new ColumnMeta("status", "状态", TYPE_STRING, true, false));
        m.column(new ColumnMeta("priority", "优先级", TYPE_STRING, true, false));
        m.column(new ColumnMeta("executionType", "执行类型", TYPE_STRING, true, false));
        m.column(new ColumnMeta("singleExecution", "单次执行", TYPE_SELECT, true, false).options("0", "1"));
        m.column(new ColumnMeta("retryOnFailure", "失败重试", TYPE_SELECT, true, false).options("0", "1"));
        m.column(new ColumnMeta("sendNotification", "发送通知", TYPE_SELECT, true, false).options("0", "1"));
        m.column(new ColumnMeta("repeatExecutionTime", "重复执行时间", TYPE_STRING, true, false));
        m.column(new ColumnMeta("weekdays", "执行星期", TYPE_STRING, true, false));
        m.column(new ColumnMeta("lastExecution", "上次执行", TYPE_STRING, true, false));
        m.column(new ColumnMeta("nextExecution", "下次执行", TYPE_STRING, true, false));
        m.column(new ColumnMeta("createTime", "创建时间", TYPE_DATETIME, false, false));
        m.column(new ColumnMeta("updateTime", "更新时间", TYPE_DATETIME, false, false));
        m.column(new ColumnMeta("isDelete", "已删除", TYPE_BOOLEAN, false, false));
        entities.put(m.getKey(), m);
    }

    // ==================== 阶段二：用户 ====================
    // 密码列特殊处理：读取脱敏、写入前哈希；留空表示不修改。
    // 该实体同时被 mock 快照生成器列入排除名单，绝不进入版本库。

    private void registerUser() {
        EntityMeta m = new EntityMeta("user", "用户", "t_user");
        m.column(new ColumnMeta("id", "ID", TYPE_NUMBER, false, false));
        m.column(new ColumnMeta("userAccount", "账号", TYPE_STRING, true, true));
        m.column(new ColumnMeta("userPassword", "密码", TYPE_STRING, true, false)
                .encoder(ENCODER_MD5_PASSWORD)
                .masked()
                .hint("留空表示不修改；填写后按 md5(盐+明文) 存储，与登录逻辑一致"));
        m.column(new ColumnMeta("createTime", "创建时间", TYPE_DATETIME, false, false));
        m.column(new ColumnMeta("updateTime", "更新时间", TYPE_DATETIME, false, false));
        m.column(new ColumnMeta("isDelete", "已删除", TYPE_BOOLEAN, false, false));
        entities.put(m.getKey(), m);
    }

    // ==================== 对外访问 ====================

    /**
     * 取实体元数据，不存在即抛异常（由控制器转成 40000 参数错误）。
     */
    public EntityMeta require(String key) {        EntityMeta m = key == null ? null : entities.get(key);
        if (m == null) {
            throw new IllegalArgumentException("未知的实体: " + key);
        }
        return m;
    }

    public boolean exists(String key) {
        return key != null && entities.containsKey(key);
    }

    public Collection<EntityMeta> all() {
        return Collections.unmodifiableCollection(entities.values());
    }

    /**
     * 转成前端使用的 VO。
     */
    public DataAdminEntityVo toVo(EntityMeta m) {
        DataAdminEntityVo vo = new DataAdminEntityVo();
        vo.setKey(m.getKey());
        vo.setLabel(m.getLabel());
        vo.setTable(m.getTable());
        vo.setSoftDelete(m.isSoftDelete());
        vo.setComposite(m.isComposite());
        vo.setChildEntityKey(m.getChildEntityKey());
        vo.setChildForeignKey(m.getChildForeignKey());
        vo.setDerivedColumns(new ArrayList<>(m.getDerivedColumns()));

        List<DataAdminColumnVo> cols = new ArrayList<>();
        for (ColumnMeta c : m.getColumns()) {
            DataAdminColumnVo cv = new DataAdminColumnVo();
            cv.setName(c.getName());
            cv.setLabel(c.getLabel());
            cv.setType(c.getType());
            cv.setEditable(c.isEditable() && !m.getDerivedColumns().contains(c.getName()));
            cv.setRequired(c.isRequired());
            cv.setSortable(c.isSortable());
            cv.setFilterable(c.isFilterable());
            cv.setOptions(c.getOptions());
            cv.setHint(c.getHint());
            cv.setMasked(c.isMasked());
            cols.add(cv);
        }
        vo.setColumns(cols);
        return vo;
    }

    /**
     * 全部实体的 VO 列表。
     */
    public List<DataAdminEntityVo> allVos() {
        List<DataAdminEntityVo> list = new ArrayList<>();
        for (EntityMeta m : entities.values()) {
            list.add(toVo(m));
        }
        return list;
    }
}
