# Java LightRAG 可选 Apache AGE 图后端实施计划

**Goal:** 为 `POSTGRES` 存储类型增加**可选**的 Apache AGE 图后端，实现方式对齐官方 Python 版 `PGGraphStorage`（`D:\ai-code\LightRAG\lightrag\kg\postgres_impl.py:7267-9572`）；默认行为完全不变（原生 SQL 表 `PostgresGraphStore`），AGE 为显式开启。

**Architecture:** 同一套 `PostgresStorageProvider`/同一数据源，图存储换成 `PostgresAgeGraphStore`（新），由 `PostgresGraphBackend { TABLE, AGE }` 二选一（core 构造器重载 + starter 属性 `lightrag.storage.postgres.graph-backend`）。AGE 引导（版本门 → `CREATE EXTENSION` → `create_graph` → label/索引）只在 AGE 模式下执行一次，不新增 schema migration、不动 schema 版本号（当前 v7）。AGE 写操作与关系型表共用 `writeAtomically` 的同一 JDBC 事务（`ag_catalog.cypher()` 是普通事务型 SQL），回滚天然覆盖图写入——无需补偿逻辑。

**Tech Stack:** Java 17、Gradle、JUnit 5 + AssertJ、Testcontainers、pgjdbc、Jackson（core 已有依赖）、Apache AGE（PG 扩展）。

---

## 背景与上游对照

上游 Python 通过 `LIGHTRAG_GRAPH_STORAGE` 在 `PGTableGraphStorage`（原生表）与 `PGGraphStorage`（AGE）之间二选一，两者共用同一 PostgreSQL 数据库。本计划只移植 `PGGraphStorage` 的图读写语义，落地为 Java 的第二个图后端。

### 上游语义清单（本计划要复刻的行为）

| 语义 | 上游位置 | 照搬内容 |
|---|---|---|
| 图命名 | `postgres_impl.py:7284-7307` | `workspace` 为空/`"default"` → 图名 = namespace（`chunk_entity_relation`，`namespace.py:20`）；否则 `{safe_workspace}_{namespace}`，sanitize `[^A-Za-z0-9_]`→`_`，按 63 字节截断（`_PG_NAME_MAX_BYTES=63`） |
| 版本门 | `:119-194, 931-1225` | 同时读 `pg_extension.extversion`（已装版本）与 `pg_available_extensions.default_version`（磁盘二进制版本），取较高者；`>= 1.8.0` 拒绝启动（`AGE_FIRST_UNSUPPORTED_VERSION=(1,8,0)`，apache/age#2500：1.8.0 起 `id()` 返回 graphid，`get_knowledge_graph` 失败甚至 SIGSEGV 整个实例）；版本不可解析/不可读 → 一律视为未验证，拒绝；覆盖开关 `POSTGRES_AGE_ALLOW_UNSUPPORTED_VERSION=true`（bool 语义：`true/1/yes/t/on`，大小写不敏感，`utils.py:346`）；两者都缺席 → 放行（无可门禁对象）|
| 扩展与图引导 | `:1218, 1246-1316, 7336-7440` | `CREATE EXTENSION IF NOT EXISTS AGE CASCADE`；`SET search_path`（Java 改为全限定，见「偏差」）；查 `ag_catalog.ag_graph`（`left($1,63)::name`）不存在才 `create_graph()`，容忍并发建图竞态（3F000 / 23505）；label `base`（顶点）/`DIRECTED`（边）只在缺失时 `create_vlabel`/`create_elabel`；12 条 `CREATE INDEX CONCURRENTLY IF NOT EXISTS`（含 `entity_idx_node_id`、`entity_node_id_gin_idx`）+ `ALTER TABLE {g}."DIRECTED" CLUSTER ON directed_sid_idx` |
| 顶点 upsert | `:7797-7827, 7564-7592` | `MERGE (n:base {entity_id: $entity_id}) SET n += {props} RETURN n`；属性内联（key 反引号包裹并转义 `` ` ``→``` `` ```）；实体 id 走参数 |
| 边 upsert | `:7829-7879, 7984-8104` | `MATCH` 两端 → `OPTIONAL MATCH (source)-[old:DIRECTED]-(target) DELETE old` → `CREATE (source)-[r:DIRECTED {props}]->(target) RETURN r`（AGE 边属性只有内联在 CREATE 里才会持久化）；返回空集 = 端点在图中不存在 → 报错（上游 `PGGraphEdgeWriteLostError`，先探针两个端点以点名缺失者）|
| 删除 | `:8317-8447, 9557-9572` | `delete_node`: `MATCH (n:base {entity_id: "..."}) DETACH DELETE n`；`remove_nodes`: `MATCH (n:base) WHERE n.entity_id IN [...] DETACH DELETE n`（按 1000/批分块，同一事务）；`remove_edges`: `MATCH (a:base {entity_id: "s"})-[r]-(b:base {entity_id: "t"}) DELETE r`；drop: `MATCH (n) DETACH DELETE n` |
| 读 | `:8449-8527, 8662-8887, 9256-9382` | `get_all_nodes`: `SELECT properties FROM {g}.base`；`get_all_edges`: `{g}."DIRECTED"` join `base` 取端点 `entity_id` + `r.properties`；`get_nodes_batch` 用 `unnest + agtype_access_operator = (to_json(v)::text)::agtype`；`get_all_labels`: `MATCH (n:base) WHERE n.entity_id IS NOT NULL RETURN DISTINCT n.entity_id ORDER BY n.entity_id` |
| 记录解析 | `:7451-7562` | 顶点/边返回 `{...}::vertex` / `{...}::edge` 文本，按最后一个 `::` 切分后 JSON 解析出 properties |
| 注入防护 | `:374-411, 7309-7334` | `_dollar_quote`：找唯一 `$AGEi$` tag，且拒绝 `s.endsWith(wrapper[:-1])`（接缝处会提前闭合）；`_normalize_node_id`：去 NUL → 先转义 `\` 再转义 `"` |

### Java 侧对接点（本计划会改到的地方）

| 位置 | 现状 | 改动 |
|---|---|---|
| `PostgresStorageProvider` | 字段/视图/锁包装全按 `PostgresGraphStore` 具体类型构造（`:59, 143, 167, 332`）| 字段加宽为 `MutableGraphStore`；新增 `PostgresGraphBackend` 选择与 4 个构造器重载；`newAtomicView`/`truncateAll` 按后端分支 |
| `PostgresStorageConfig` | public record（6 字段，下游 aiplatform 直接依赖） | **不动**（加字段破坏 API） |
| `GraphStore` 接口 | `labels()/searchLabels()/getKnowledgeGraph()` 有默认实现（全量扫描） | AGE 只覆写 `labels()`（原生 cypher，上游 `get_all_labels` 照搬）；其余保持默认（与所有 Java 后端一致，见「偏差」） |
| `RelationCanonicalizer` | `relationId = "rel-"+md5(src+tgt)`（不可逆）| AGE 边属性持久化 `relation_id`，否则 `loadRelation(relationId)`/`deleteRelations` 无法按 id 定位（Java 独有需求） |
| `PostgresSchemaManager` | 迁移 v3-v7，`PostgresStorageProviderTest` 断言版本 7 | **不动**（AGE 引导是 opt-in 独立 bootstrap，不进迁移版本） |
| starter `LightRagProperties.PostgresProperties` | jdbcUrl/…/tablePrefix | 新增 `graphBackend`（枚举 `PostgresGraphBackend`，默认 `TABLE`），`lightrag.storage.postgres.graph-backend: table|age` |
| starter 两处 `switch (type) { case POSTGRES ... }` | `LightRagAutoConfiguration:241-243`、`SpringWorkspaceStorageProvider:152-154` | 透传 backend |
| `PostgresNeo4jStorageProvider` 等 | 自建图（Neo4j/镜像表） | **不动**（AGE 仅适用于 `POSTGRES` 类型）|

### 属性映射（Java record ↔ AGE 顶点/边属性）

顶点（`base`）属性（命名对齐上游）：

| 属性 | Java 字段 | 说明 |
|---|---|---|
| `entity_id` | `EntityRecord.id()` | MERGE 键（上游同名） |
| `name` | `name()` | |
| `entity_type` | `type()` | 上游同名 |
| `description` | `description()` | |
| `aliases` | `aliases()` | `RelationCanonicalizer.joinValues()`（`<SEP>` 连接）；读取时 `splitValues` |
| `source_id` | `sourceChunkIds()` | 同上（上游同名） |

边（`DIRECTED`）属性：

| 属性 | Java 字段 | 说明 |
|---|---|---|
| `relation_id` | `relationId()` | **Java 独有**（md5 不可逆）；读取缺失时按 `RelationCanonicalizer` 从 src/tgt 反推 |
| `src_id` / `tgt_id` | `srcId()`/`tgtId()` | 上游同名 |
| `keywords` / `description` / `weight` / `source_id` / `file_path` | 同名 | `weight` 数值；`source_id` 为 joinValues 字符串 |

### 与上游的刻意偏差（逐条有理由）

1. **不做 per-edge advisory lock**（上游 `:8014-8024`）：Java 写路径全部经 `PostgresStorageProvider` 工作区级互斥锁（跨进程 PG advisory lock，`writeAtomically`/`withWriteLock`），边写不可能并发交错；上游需要它是因为其 graph-edit HTTP 端点绕过单写者门。边写仍保留「返回空集 → 报错」的丢写保护与端点探针。
2. **事务内 `SET LOCAL search_path = ag_catalog, "$user", public`**（实施修正，2026-10-02 容器实测推翻原计划「不改 search_path」）：原计划试图只靠全限定名避开会话状态，但实测 AGE 1.6.0 显示 `MERGE` 的内部改写会发出未限定的 `@>` 运算符，search_path 缺 `ag_catalog` 时报 `operator does not exist: ag_catalog.agtype @> ag_catalog.agtype`。与方法对齐上游 `:1246-1248`，但改用 `SET LOCAL`：设置随 commit/rollback 自动失效，不会残留到连接池；SQL 其余部分仍全限定 `ag_catalog.cypher` / `ag_catalog.agtype` / `ag_catalog.agtype_access_operator` / `ag_catalog.ag_graph` / `ag_catalog.ag_label`。
3. **`CREATE EXTENSION` 失败 → 启动即失败**（上游只告警 `:1220-1225`）：Java 侧是用户显式选择 `graph-backend: age`，扩展装不上则所有图操作都不可能成功；启动即报清晰错误（附「装 AGE 或改回 table」指引）优于运行期才炸。版本门的全部拒绝路径与上游一致。
4. **`installed 缺席 + available 不可读` 状态直接拒绝**（上游告警后跳过 CREATE EXTENSION、把烂摊子留给后续操作报错 `:1070-1081`）：同样为 fail-fast，安全语义（不装未验证版本）不变。
5. **读面板保持接口默认实现**（`searchLabels`/`getKnowledgeGraph`）：Java 各后端（含原生表 PG、Neo4j）都用默认实现，Java 语义（如 `searchLabels` 的精确匹配优先、`getKnowledgeGraph` 的度序 BFS）由接口契约定义；上游的 native SQL 版评分/排序语义本就与 Java 契约不同。`labels()` 例外——原生 cypher 与契约完全一致且省一次全图解析。
6. **`PostgresStorageProvider.graphStore()` 返回的锁包装 `LockedGraphStore` 不实现 `MutableGraphStore`**：与现状一致（删除路径在 Java 走 `restore` 快照重建，不直接调 `deleteEntities`）；AGE store 实现 `MutableGraphStore` 以供快照/测试/未来直用。

---

## File Map

**lightrag-core**
- Create: `lightrag-core/src/main/java/io/github/lightrag/storage/postgres/PostgresGraphBackend.java`（枚举 TABLE/AGE）
- Create: `lightrag-core/src/main/java/io/github/lightrag/storage/postgres/PostgresAgeSupport.java`（图名 / dollar-quote / normalize / 属性内联 / JSON 编码 / 版本解析，纯函数）
- Create: `lightrag-core/src/main/java/io/github/lightrag/storage/postgres/PostgresAgeBootstrap.java`（版本门 + 扩展 + 图 + label + 索引）
- Create: `lightrag-core/src/main/java/io/github/lightrag/storage/postgres/PostgresAgeGraphStore.java`（`MutableGraphStore` 实现）
- Modify: `lightrag-core/src/main/java/io/github/lightrag/storage/postgres/PostgresStorageProvider.java`（重载 + 分支 + widen 字段 + truncateAll）
- Create: `lightrag-core/src/test/java/io/github/lightrag/storage/postgres/PostgresAgeSupportTest.java`
- Create: `lightrag-core/src/test/java/io/github/lightrag/storage/postgres/PostgresAgeVersionGateTest.java`
- Create: `lightrag-core/src/test/java/io/github/lightrag/storage/postgres/PostgresAgeGraphStoreTest.java`（Testcontainers，AGE 镜像）

**lightrag-spring-boot-starter**
- Modify: `LightRagProperties.java`（`PostgresProperties.graphBackend`）
- Modify: `LightRagAutoConfiguration.java`（POSTGRES 分支）
- Modify: `SpringWorkspaceStorageProvider.java`（POSTGRES 分支）

**文档**
- Modify: `README.md` / `README_zh.md`（存储矩阵加 AGE 一行 + 开启方式）

---

## Task 1: `PostgresAgeSupport`（纯函数层）+ 单测

- [ ] 1.1 新建 `PostgresAgeSupport`（包私有 final，全部 static）：
  - `String graphName(String workspaceId)`：`strip()` 后为空或 `equalsIgnoreCase("default")` → `"chunk_entity_relation"`；否则 `sanitize(workspace) + "_chunk_entity_relation"`；最后按 63 字符截断（sanitize 后全 ASCII，字符=字节）。`sanitize` = `replaceAll("[^A-Za-z0-9_]", "_")`。
  - `String dollarQuote(String value)`：tag `$AGE{i}$`，条件 `!value.contains(wrapper) && !value.endsWith(wrapper.substring(0, wrapper.length() - 1))`（接缝防护），i 从 1 递增。
  - `String normalizeNodeId(String)`：`replace("\u0000","")` → `replace("\\","\\\\")` → `replace("\"","\\\"")`（顺序不可换）。
  - `String formatProperties(Map<String, Object>)`：`` `key`: value `` 逐项拼接（key 内 `` ` ``→``` `` ```）；值经 `jsonValue`（String → 转义编码、非 ASCII 原样保留；Number/Boolean → 字面量；其他 → 按 String 编码）。
  - `String jsonString(String)`：端口 `json.dumps(..., ensure_ascii=False)` 语义——`"`、`\`、控制字符（`<0x20`，含 `\b\f\n\r\t` 专用短转义）转义，`>=0x20` 原样。
  - `record AgeVersion(int major, int minor, int patch)` + `AgeVersion parseVersion(String raw)`：正则全匹配 `[0-9]+\.[0-9]+\.[0-9]+`（不补位、不收符号/下划线），不匹配返回 null。
- [ ] 1.2 `PostgresAgeSupportTest`：图名（default/大小写/空白/特殊字符 sanitize/超长截断 63）；dollar quote（上游 docstring 三例 + `endsWith("$AGE1")` 接缝例 + 含 `$AGE2$` 时跳到 3）；normalize（NUL/反斜杠/引号/注入串 `a" } MATCH (n) DETACH DELETE n //`）；formatProperties（反引号 key、中文/emoji 原样、`\n` 转义、weight 数值）；parseVersion（`1.7.0` 通过，`1.8.0`、`2.0.0`、`1.8`、`1.8.0-dev`、`""`、`null`）。

## Task 2: 版本门（纯决策函数）+ 单测

- [ ] 2.1 `PostgresAgeBootstrap` 内实现（或独立小类）`GateDecision decide(Probe installed, Probe available, boolean override)`；`Probe(boolean readable, String raw)`，`readable=false` = 查询本身失败。决策表（与上游 `:931-1215` 对齐）：

| # | 输入状态 | override=false | override=true |
|---|---|---|---|
| 1 | `!installed.readable` | 拒绝（无法确定版本） | 放行（降级告警）|
| 2 | `installed.raw==null && !available.readable` | 拒绝（无法确认，且即将安装未验证版本）*） | 放行（降级告警）|
| 3 | `installed.raw==null && available.raw==null && available.readable` | 放行（无 AGE，无可门禁对象，INFO 日志） | 同左 |
| 4 | 任一可解析版本 `>= 1.8.0` | 拒绝（附 apache/age#2500 说明；`installed>=1.8 > available<1.8` 时用「旧库在跑、功能坏而非崩溃」专用文案）| 放行（降级告警）|
| 5 | 存在「在场但不可解析」，或（可读源都缺席以外的）无可解析版本 | 拒绝（无法确定） | 放行（降级告警）|
| 6 | 其余（可解析且 `< 1.8.0`） | 放行 | 放行 |

  \*) 上游此处为「告警并跳过 CREATE EXTENSION」（`:1064-1081`）；本 Java 版改为拒绝（理由见「偏差 4」）。
- [ ] 2.2 `override` 读取：先 `System.getenv("POSTGRES_AGE_ALLOW_UNSUPPORTED_VERSION")`，回退 `System.getProperty(同名)`（JVM 测试无法设置环境变量；系统属性是对外行为的测试化扩展，写注释说明），bool 解析 `strip().toLowerCase() ∈ {true,1,yes,t,on}`。
- [ ] 2.3 `PostgresAgeVersionGateTest`：覆盖表格全部 6 行 × override 两态 + `1.8.0>1.7.0` 高者判定的两个方向 + 空串当「不可解析」而非「缺席」。拒绝消息断言含 `1.8.0`、`POSTGRES_AGE_ALLOW_UNSUPPORTED_VERSION`、`graph-backend`（可行动指引）。

## Task 3: `PostgresAgeGraphStore` + `PostgresAgeBootstrap`

- [ ] 3.1 `PostgresAgeGraphStore implements MutableGraphStore`：
  - 构造器：`public PostgresAgeGraphStore(DataSource, String workspaceId)`；包私有 `(JdbcConnectionAccess, String workspaceId)`；内部 `graphName = PostgresAgeSupport.graphName(workspaceId)`；与 `PostgresGraphStore` 相同的 `inTransaction` 模式（原 autoCommit=true 才开/提交；已在事务中不提交，供 atomic view 复用）。
  - 统一 SQL 形态（标识符全限定；会话内先 `SET LOCAL search_path = ag_catalog, "$user", public`，见「偏差 2」实施修正）：
    - 无参：`SELECT * FROM ag_catalog.cypher($AGE1$<graph>$AGE1$::name, $AGE2$<cypher>$AGE2$::cstring) AS (n ag_catalog.agtype)`
    - 带参（并入 `dollarQuote(<graph>)::name` / `dollarQuote(<cypher>)::cstring`）：`... , ?::ag_catalog.agtype) AS (...)`，参数用 `setObject(i, json, java.sql.Types.OTHER)`（未定型参数按字面量走 agtype 输入函数；实现时在容器里验证，如不通改为 `PGobject(type="agtype")`）。
  - `saveEntity`：`MERGE (n:base {entity_id: $entity_id}) SET n += {props} RETURN n`（props 内联；params `{"entity_id": id}`）。
  - `saveRelation`：按上游 `_build_upsert_edge_sql` 原文改写（见「上游语义清单」），params `{"src_id","tgt_id"}`；`executeQuery` 空结果 → 先跑端点存在性探针（上游 `:7861-7879` 的 `unnest + EXISTS + agtype_access_operator` 原文，`createArrayOf("text", ...)` 传参）点名缺失端点，再抛 `StorageException`。
  - `loadEntity`：`MATCH (n:base {entity_id: $entity_id}) RETURN n`（参数化）→ `recordToProperties` 解析 → `EntityRecord`（`aliases`/`source_id` 走 `splitValues`；缺 `entity_type` 时回退 props 的 `type`？——不，只读 `entity_type`，缺失记 `""`）。
  - `loadRelation(relationId)`：`MATCH (a:base)-[r:DIRECTED]->(b:base) WHERE r.relation_id = $relation_id RETURN r` → 解析边属性 → `RelationRecord`。
  - `allEntities`：`SELECT properties FROM {g}.base`（原生 SQL）→ 解析 → **按 entity_id 排序**（对齐表后端 `ORDER BY id`）。
  - `allRelations`：`SELECT properties FROM {g}."DIRECTED"` → 解析 → 按 relation_id 排序。`relation_id` 缺失/空 → `RelationCanonicalizer.canonicalize(src,tgt).relationId()` 反推。
  - `findRelations(entityId)`：原生 SQL 两段 `UNION`（出边走 `r.start_id`、入边走 `r.end_id`，各用 base 表 entity_id 表达式索引过滤），`ORDER BY rid`：
    ```sql
    SELECT properties FROM (
      SELECT r.id AS rid, r.properties AS properties
      FROM {g}.base a JOIN {g}."DIRECTED" r ON r.start_id = a.id
      WHERE ag_catalog.agtype_access_operator(VARIADIC ARRAY[a.properties, '"entity_id"'::ag_catalog.agtype])
            = (to_json(?::text)::text)::ag_catalog.agtype
      UNION
      SELECT r.id, r.properties
      FROM {g}.base a JOIN {g}."DIRECTED" r ON r.end_id = a.id
      WHERE ag_catalog.agtype_access_operator(VARIADIC ARRAY[a.properties, '"entity_id"'::ag_catalog.agtype])
            = (to_json(?::text)::text)::ag_catalog.agtype
    ) sub ORDER BY rid
    ```
  - `deleteEntities`：按 1000/批分块（上游 `DEFAULT_PG_DELETE_MAX_RECORDS_PER_BATCH=1000`）：先 `MATCH (n:base) WHERE n.entity_id IN [...] RETURN n.entity_id`（列类型 text）取应删数，再同列表 `... DETACH DELETE n`（同一事务）；返回累计。
  - `deleteRelations`：同法：`MATCH (a:base)-[r:DIRECTED]->(b:base) WHERE r.relation_id IN [...] RETURN r.relation_id` + `... DELETE r`。
  - `labels()`：`MATCH (n:base) WHERE n.entity_id IS NOT NULL RETURN DISTINCT n.entity_id AS label ORDER BY n.entity_id`（`AS (label text)`，上游 `get_all_labels` 照搬）。
  - 包私有 `void clear()`：`MATCH (n) DETACH DELETE n`（drop，照搬 `:9557-9572`），供 provider `truncateAll`。
  - `recordToProperties(String agtypeText)`：按最后一个 `::` 切分 → 前缀文本 Jackson 解析为 `Map<String,Object>`（上游 `_record_to_dict`）。
- [ ] 3.2 `PostgresAgeBootstrap`（包私有）：
  - 构造 `(DataSource, String workspaceId)`；`bootstrap()` 用 `autoCommit=true` 的连接（`CREATE INDEX CONCURRENTLY` 不允许在事务块内）。
  - 步骤：版本门（Task 2，拒绝抛 `StorageException`）→ `CREATE EXTENSION IF NOT EXISTS age CASCADE`（失败即抛，附指引）→ `SELECT 1 FROM ag_catalog.ag_graph WHERE name = left(?::text, 63)::name`（无则 `SELECT ag_catalog.create_graph($AGE1$<name>$AGE1$)`，`create_graph` 不接受参数，走 dollar-quote 内联；捕获 3F000/23505 视为竞态成功）→ 查 `ag_catalog.ag_label JOIN ag_catalog.ag_graph` 现有 label，缺 `base`/`DIRECTED` 才 `create_vlabel`/`create_elabel`（捕获「已存在」类 SQLState 42P07/42710/23505 降级忽略）→ 逐条执行 12 条索引 DDL + `ALTER TABLE ... CLUSTER ON`（`IF NOT EXISTS` 幂等；索引名/定义照搬 `:7415-7428`，`::agtype` 全部全限定）。
  - 图名经 `PostgresAgeSupport.graphName(workspaceId)`；查询比较用 `left($1, 63)` 与上游一致。
- [ ] 3.3 复核 `PostgresGraphStore` 对应关系（顺序保持、`executeQuery` 返回值判空、日志前缀 `[workspace]`）。

## Task 4: `PostgresStorageProvider` 接线

- [ ] 4.1 新增 `enum PostgresGraphBackend { TABLE, AGE }`（`io.github.lightrag.storage.postgres`）。
- [ ] 4.2 构造器重载（全部 `backend` 在末位；既有 4 个构造器不动，默认 `TABLE`）：
  - `(PostgresStorageConfig, SnapshotStore, PostgresGraphBackend)`
  - `(PostgresStorageConfig, SnapshotStore, String workspaceId, PostgresGraphBackend)`
  - `(DataSource, PostgresStorageConfig, SnapshotStore, PostgresGraphBackend)`
  - `(DataSource, PostgresStorageConfig, SnapshotStore, String workspaceId, PostgresGraphBackend)`
- [ ] 4.3 私有构造器加 `PostgresGraphBackend graphBackend` 参数与字段；`Objects.requireNonNull`；在 `PostgresSchemaManager.bootstrap()` 之后、store 构造之前：`if (backend == AGE) new PostgresAgeBootstrap(jdbcDataSource, workspaceId).bootstrap();`。
- [ ] 4.4 `graphStore` 字段类型 `PostgresGraphStore` → `MutableGraphStore`；构造按后端二选一；`newAtomicView(Connection)` 同分支（`JdbcConnectionAccess.forConnection`）；`LockedGraphStore` 不变（接口 `GraphStore`，读方法齐全；写方法在构造注入口已锁）。
- [ ] 4.5 `truncateAll(connection)`：AGE 模式追加 `new PostgresAgeGraphStore(JdbcConnectionAccess.forConnection(connection), workspaceId).clear()`（同事务；随后 `restore` 重灌走同一连接上的 save 调用）。
- [ ] 4.6 既有测试不动：默认 TABLE 路径行为零变化；`PostgresStorageProviderTest` 的 schemaVersion=7 断言不受影响。

## Task 5: starter 接线

- [ ] 5.1 `LightRagProperties.PostgresProperties`：加 `private PostgresGraphBackend graphBackend = PostgresGraphBackend.TABLE;` + getter/setter（setter 容忍 null → 归一化 TABLE，与既有风格一致）。
- [ ] 5.2 `LightRagAutoConfiguration` POSTGRES 分支与 `SpringWorkspaceStorageProvider.createManagedProvider` POSTGRES 分支：读取 `properties.getStorage().getPostgres().getGraphBackend()`（null → TABLE）透传。
- [ ] 5.3 README（英/中）：存储配置表在 `postgres` 行下补 `graph-backend: table|age` 说明（AGE 需服务端安装 Apache AGE；`POSTGRES_AGE_ALLOW_UNSUPPORTED_VERSION` 覆盖开关；AGE 与 `POSTGRES_NEO4J` 等类型无关）。

## Task 6: 集成测试（Testcontainers）+ 全量验证

- [ ] 6.1 先探测可用镜像：`docker manifest inspect apache/age:release_PG16_3.0.0`（候选：`apache/age:release_PG16_*`、`gzdaniel/postgres-for-rag:pg18-age-pgvector`）。AGE 容器需 `shared_preload_libraries=age`（Testcontainers `.withCommand("postgres", "-c", "shared_preload_libraries=age")`，镜像自带则省略）。
  - 注意：官方 `apache/age` 镜像**不含 pgvector**，无法走完整 `PostgresStorageProvider`（其 schema bootstrap 需要 `CREATE EXTENSION vector`）。因此：
    - `PostgresAgeGraphStoreTest` 直接测 `PostgresAgeBootstrap` + `PostgresAgeGraphStore`（不全栈），用 AGE 镜像；
    - 完整 provider 级 AGE 用例仅在有双扩展镜像时启用；否则退出为 env-gated 手动用例（沿用 `LIGHTRAG_ARCADEDB_IT` 式约定，读完该用例后按同型实现），并在计划/README 记录。
- [ ] 6.2 `PostgresAgeGraphStoreTest` 用例：bootstrap 两次幂等（重复构造图/索引不报错）；entity 往返（aliases/中文/emoji/双引号/`$AGE1$` 注入串）；relation 往返 + 端点缺失报错（消息点名缺失端点）；重写 upsert 替换旧边（旧边不残留、weight 更新）；`findRelations` 双向；`deleteEntities` 级联删边、返回数；`deleteRelations` 按 relation_id；`allEntities`/`allRelations` 与写值一致；`labels()`；`clear()`；`loadRelation` 缺失 → `Optional.empty()`。
- [ ] 6.3 `PostgresStorageProvider` AGE 全栈用例（镜像可用时）：默认 TABLE 行为不变的回归 + AGE 模式 `writeAtomically` 回滚后图数据不落库（事务性验证）。
- [ ] 6.4 `./gradlew build` 全绿；Docker 不可用时集成用例按既有门禁跳过、其余全绿。

---

## 验收标准

1. 默认路径零变化：不配置 `graph-backend` 时行为与现状完全一致；既有测试（含 `PostgresStorageProviderTest` 版本断言）不改一行全绿。
2. `graph-backend: age` 时：启动完成版本门（≥1.8.0 拒绝，`POSTGRES_AGE_ALLOW_UNSUPPORTED_VERSION` 可覆盖）、扩展、图、label、索引引导；`saveEntity/saveRelation/load*/findRelations/delete*/labels` 在 AGE 图上有正确语义；`writeAtomically` 事务回滚覆盖 AGE 写入；`restore`/`truncateAll` 清空并重灌 AGE 图。
3. 与官方 Python `PGGraphStorage` 的图命名一致（`default` → `chunk_entity_relation`，工作区 → `{ws}_chunk_entity_relation`），属性命名一致（`entity_id/entity_type/source_id/src_id/tgt_id/keywords/description/weight/file_path`），可无损对接同库数据。
4. `PostgresAgeSupportTest` / `PostgresAgeVersionGateTest` 全绿（含注入向量）；AGE 集成用例在 Docker 可用环境下全绿。

## 风险与已知限制

- AGE 镜像可得性：若 Docker Hub 无可用 AGE 镜像（或无法拉取），集成用例降级为 env-gated，行为验证推迟到有环境的机器上执行（代码按上游原文照搬，风险集中在 pgjdbc 的 agtype 参数绑定与文本解析两处，Task 3.1 已给出回退方案）。
- pgjdbc 对 `?::ag_catalog.agtype` 的绑定：`setObject(i, json, Types.OTHER)` 首选；若驱动发送方式与 agtype 输入函数不兼容，回退 `PGobject("agtype", json)` 或 `?::text` + 服务端 cast（实现首日在容器内定案，不拖到收尾）。
- AGE 版本差异（1.5/1.6/1.7）对 `agtype_access_operator(VARIADIC ARRAY[...])`、`properties(r)` 返回形态的影响：集成用例覆盖断言；若遇到版本行为差，优先与上游同版本对齐。
