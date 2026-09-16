# db/ —— 数据库结构交付

## 文件

- `schema.sql`：`db_ant` 的**表结构**（17 张表），由
  `mysqldump --no-data --skip-add-drop-table` 生成。

## 三条安全约束

1. **不含数据**：`--no-data`，只有 `CREATE TABLE`，没有 `INSERT`。
   现场业务数据（站点、路线、地图、传感器记录）由现场机器自身保有。

2. **不含 `DROP TABLE`**：刻意加了 `--skip-add-drop-table`。
   这样即使误在已有数据的库上执行，也只会因「表已存在」而失败，**不会删表**。

3. **安装时只在库不存在时导入**：`tools/web_deb_packaging/scripts/postinst` 会先检查
   `db_ant` 是否存在，存在则直接跳过 —— 升级/重装**零覆盖**。

## 为什么没有默认种子数据

参照项目 `IPC_Web_service_deb` 的 `deliverable/db/` 含一份
`laser_obstacle_16groups.sql`（16 组默认配置），因为那是**新机器从零投入使用**的场景。

本项目不同：现场的站点、路线、地图与参数是**长期积累的生产数据**，
凭空造一份默认数据反而危险（可能被误导入而覆盖现场配置）。
因此这里只交付结构；需要初始化数据时，应从现场实际库导出并单独评审。

## 重新生成 schema.sql

结构变更后（例如新增字段）需要重新生成：

```bash
mysqldump -h 127.0.0.1 -u root -proot \
  --no-data --skip-add-drop-table --skip-comments --no-tablespaces \
  --default-character-set=utf8mb4 db_ant > deploy/db/schema.sql
```

生成后请核对：`CREATE TABLE` 数量应为 17，且不应出现 `DROP TABLE` 与 `INSERT`。
