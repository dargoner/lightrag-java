# Java LightRAG Maven Central Publishing Design

## Context

当前仓库已经是标准的 Gradle 多模块 Java 17 项目，包含：

- `lightrag-core`
- `lightrag-spring-boot-starter`
- `lightrag-spring-boot-demo`

仓库根工程当前使用：

- `group = "io.github.lightragjava"`
- `version = "0.1.0-local-SNAPSHOT"`

现状只在 `lightrag-core` 中配置了最基础的 `maven-publish`，尚未具备发布到 Maven Central
所需的完整能力：

- `groupId` 与 GitHub 仓库所有者 `dargoner` 不一致，不利于 Central namespace 校验
- `lightrag-spring-boot-starter` 未纳入发布
- 未配置签名
- 未配置完整 POM 元数据
- 未提供 Central Portal 凭据读取方式
- 未提供面向发布的验证命令和文档

用户希望将该项目发布到公用 Maven 仓库，并已确认本次发布统一采用：

- `groupId = io.github.dargoner`

## Goal

将仓库改造为可发布到 Maven Central 的状态，并提供清晰、可重复执行的本地发布流程。

具体目标：

- 发布 `lightrag-core`
- 发布 `lightrag-spring-boot-starter`
- 不发布 `lightrag-spring-boot-demo`
- 统一使用 `io.github.dargoner` 作为 `groupId`
- 为可发布模块生成 `sources`、`javadoc`、签名和完整 POM
- 通过环境变量或 `gradle.properties` 提供发布凭据，不将敏感信息提交到仓库
- 提供本地验证和正式发布命令说明

## Non-Goals

- 本次不处理在 Maven Central 发布后的自动同步徽章、站点或公告
- 本次不发布 `lightrag-spring-boot-demo`
- 本次不引入额外的 CI 发布流水线
- 本次不变更业务代码行为，仅处理发布工程化配置和文档
- 本次不解决 Sonatype Central Portal 账号、namespace 注册或 GPG 密钥生成本身

## Recommended Approach

### Approach A: 纯原生 Gradle 发布链路

- 仅使用 `maven-publish` 与 `signing`
- 手工维护所有 publication、签名、POM 和上传逻辑

优点：

- 依赖最少，行为透明
- 排障时只需理解 Gradle 官方能力

缺点：

- Central Portal 发布上传链路需要自行拼装
- 对首次发布成本偏高
- 后续维护 bundle/upload 细节容易分散在脚本里

### Approach B: 使用成熟的 Central 发布插件，并保留显式 POM/模块配置

这是推荐方案。

- 使用社区成熟插件对接 Maven Central 发布动作
- 仓库内部仍然显式维护：
  - `group`
  - `version`
  - 发布模块范围
  - POM 元数据
  - 凭据读取

优点：

- 落地速度快
- 对 Maven Central 所需产物和发布流程支持更完整
- 减少手工拼装上传链路的错误概率

缺点：

- 引入一层插件约定
- 后续排障需要同时理解插件与 Gradle

### Approach C: 只构建签名产物，上传完全手工处理

- Gradle 只负责生成 jar、sources、javadoc、pom 和签名
- Portal 上传由人工或外部脚本执行

不推荐，因为发布体验最差，容易产生“本地配置对，但上线步骤缺漏”的问题。

## Final Design

采用 Approach B。

### 1. 坐标与版本策略

根工程统一改为：

- `group = "io.github.dargoner"`
- `version` 由本地开发版本切换为可发布版本

默认保留一个非正式开发版本值，正式发布时通过 Gradle 属性覆盖，例如：

- 本地开发：`0.1.0-SNAPSHOT`
- 正式发布：`-Pversion=0.1.0`

不再保留 `-local-SNAPSHOT` 这种无法直接用于对外发布的版本命名。

### 2. 发布模块边界

仅以下模块参与发布：

- `lightrag-core`
- `lightrag-spring-boot-starter`

`lightrag-spring-boot-demo` 保持应用模块身份，不参与 `MavenPublication`。

实现上通过“只在明确的发布模块里应用发布插件和 publication 配置”保证边界清晰，
而不是让所有 subproject 一刀切开启发布。

### 3. 根工程统一维护公共发布约定

在根工程中统一维护：

- 组织坐标
- 仓库元数据
- 开源协议元数据
- GitHub SCM 信息
- 开发者信息
- Central Portal 凭据读取
- GPG 签名读取

公共信息建议以 Gradle convention 的形式集中在根 `build.gradle.kts` 中下发给
可发布模块，避免 `core` 与 `starter` 各自复制同一套 POM 配置。

POM 至少包含：

- `name`
- `description`
- `url`
- `licenses`
- `developers`
- `scm`

其中仓库地址统一指向：

- `https://github.com/dargoner/lightrag-java`

### 4. 模块发布配置

`lightrag-core` 与 `lightrag-spring-boot-starter` 统一具备：

- `java { withSourcesJar(); withJavadocJar() }`
- `maven-publish`
- `signing`

模块 publication 要求：

- `artifactId` 分别为 `lightrag-core` 和 `lightrag-spring-boot-starter`
- starter 的 POM 依赖关系中正确声明对 core 的依赖
- 生成标准 jar、sources jar、javadoc jar、pom 和签名文件

`starter` 当前尚未声明发布配置，因此本次改造需要补齐。

### 5. 凭据与签名策略

所有敏感信息均从外部读取，不写入版本库。

支持两类来源：

- `~/.gradle/gradle.properties`
- 环境变量

需要支持的参数包括：

- Central Portal username / token
- GPG key id
- GPG private key
- GPG password

优先支持内存方式导入 ASCII-armored 私钥，避免要求用户必须在本机预先安装并配置
`gpg` 可执行程序。

### 6. 发布任务与本地验证

仓库需要提供两类明确命令：

验证命令：

- 运行发布相关模块测试
- 生成并检查 publication
- 发布到本地 Maven 仓库用于验证依赖关系

正式发布命令：

- 由插件提供的 publish / upload 任务发布到 Maven Central

文档中必须明确区分：

- “构建成功”
- “产物已生成”
- “本地仓库验证通过”
- “已正式发布到 Central”

避免用户将这些阶段混为一谈。

### 7. README 文档补充

README 新增发布章节，至少说明：

- 需要准备的 Sonatype Central Portal 账号和 namespace
- 如何生成 Portal token
- 如何准备 GPG 私钥
- 本地 `gradle.properties` 示例
- 验证命令
- 正式发布命令

文档中不直接嵌入真实凭据，只给占位示例。

### 8. 测试与验证策略

本次改造不新增业务功能测试，而是加强发布链路验证。

至少验证以下内容：

- `lightrag-core:test` 继续通过
- `lightrag-spring-boot-starter:test` 继续通过
- 发布模块能生成 sources/javadoc
- publication 能生成完整 POM
- starter 发布描述中包含对 core 的依赖
- 发布到本地 Maven 仓库后，starter 可以被正常解析

若 Central 发布插件支持 dry-run 或 staging 校验，优先纳入本地验证流程。

## Risks

- 若 `groupId` 未同步改全，最终产物坐标会混乱
- 若 starter 未发布，外部用户无法通过 starter 依赖使用 Spring Boot 集成
- 若 POM 元数据不完整，Central 会拒绝发布
- 若签名读取方式设计不兼容常见环境，本地发布门槛会过高
- 若 README 不补充发布说明，后续维护者难以复现发布流程

## Success Criteria

- 仓库对外发布坐标统一为 `io.github.dargoner`
- `lightrag-core` 与 `lightrag-spring-boot-starter` 均可生成 Central 所需产物
- 敏感凭据不入库，且本地可通过环境变量或 `gradle.properties` 完成发布配置
- README 提供完整、可执行的发布说明
- 本地验证链路能证明发布产物结构正确，并且现有模块测试不回归
