# Java LightRAG Maven Central Publishing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 `lightrag-core` 和 `lightrag-spring-boot-starter` 改造成可发布到 Maven Central 的模块，统一使用 `io.github.dargoner` 坐标，并补齐 README 发布说明。

**Architecture:** 在根 `build.gradle.kts` 中集中维护发布插件、公共 POM 元数据、签名与 Central Portal 凭据约定，仅对 `lightrag-core` 和 `lightrag-spring-boot-starter` 应用发布能力。发布验证以 Gradle publication 产物、本地 Maven 仓库安装以及 README 文档为主，不改动业务模块行为。

**Tech Stack:** Gradle Kotlin DSL, Java 17, Maven Publish, signing, Vanniktech Gradle Maven Publish Plugin, Maven Central Portal

---

### Task 1: 建立根工程的公共发布约定

**Files:**
- Modify: `build.gradle.kts`
- Modify: `gradle.properties`

- [ ] **Step 1: 先跑 starter 发布任务，确认当前确实没有完整发布能力**

Run: `./gradlew --console=plain --no-daemon :lightrag-spring-boot-starter:publishToMavenLocal`

Expected: FAIL，提示 `publishToMavenLocal` 任务缺失，或 starter 没有 publication 可发布。

- [ ] **Step 2: 先跑 root properties 检查，确认当前坐标还是旧 namespace**

Run: `./gradlew --console=plain --no-daemon properties | rg "^group:|^version:"`

Expected: 输出包含 `group: io.github.lightragjava` 与 `version: 0.1.0-local-SNAPSHOT`。

- [ ] **Step 3: 在根构建脚本中切换坐标、声明发布插件版本，并建立发布模块名单**

```kotlin
plugins {
    id("com.vanniktech.maven.publish") version "0.36.0" apply false
}

group = "io.github.dargoner"
version = providers.gradleProperty("releaseVersion").orElse("0.1.0-SNAPSHOT").get()

repositories {
    mavenCentral()
}

val publishedProjects = setOf(
    project(":lightrag-core").path,
    project(":lightrag-spring-boot-starter").path,
)

subprojects {
    apply(plugin = "java")

    group = rootProject.group
    version = rootProject.version

    extensions.configure<org.gradle.api.plugins.JavaPluginExtension>("java") {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(17))
        }
    }

    repositories {
        mavenCentral()
    }

    if (path in publishedProjects) {
        apply(plugin = "com.vanniktech.maven.publish")
    }
}
```

- [ ] **Step 4: 在 `gradle.properties` 中移除本地专用版本，并补充发布说明注释**

```properties
org.gradle.java.installations.auto-download=true
# Release versions are supplied via -PreleaseVersion=...
```

- [ ] **Step 5: 重新跑 properties，确认坐标切到了新 namespace 且默认版本可覆盖**

Run: `./gradlew --console=plain --no-daemon properties -PreleaseVersion=0.1.0 | rg "^group:|^version:"`

Expected: 输出包含 `group: io.github.dargoner` 与 `version: 0.1.0`。

- [ ] **Step 6: 提交根工程公共发布约定**

```bash
git add build.gradle.kts gradle.properties
git commit -m "build: add shared Maven Central publishing conventions"
```

### Task 2: 为 `lightrag-core` 接入完整 publication、POM 与签名约定

**Files:**
- Modify: `lightrag-core/build.gradle.kts`
- Test: `lightrag-core/build/publications/maven/pom-default.xml`

- [ ] **Step 1: 先生成当前 core 的 POM，确认缺少 Central 必需元数据**

Run: `./gradlew --console=plain --no-daemon :lightrag-core:generatePomFileForMavenPublication`

Expected: FAIL 或生成的 POM 缺少 `name`、`description`、`licenses`、`developers`、`scm` 等字段。

- [ ] **Step 2: 在 core 模块中保留 Java 产物扩展，并补上显式发布坐标和简介**

```kotlin
plugins {
    `java-library`
}

description = "Framework-neutral Java SDK for a LightRAG-style indexing and retrieval pipeline."

java {
    withJavadocJar()
    withSourcesJar()
}

mavenPublishing {
    coordinates("io.github.dargoner", "lightrag-core", version.toString())

    publishToMavenCentral()
    signAllPublications()

    pom {
        name.set("lightrag-core")
        description.set(project.description)
        url.set("https://github.com/dargoner/lightrag-java")
        licenses {
            license {
                name.set("Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
            }
        }
        developers {
            developer {
                id.set("dargoner")
                name.set("dargoner")
            }
        }
        scm {
            url.set("https://github.com/dargoner/lightrag-java")
            connection.set("scm:git:https://github.com/dargoner/lightrag-java.git")
            developerConnection.set("scm:git:ssh://git@github.com/dargoner/lightrag-java.git")
        }
    }
}
```

- [ ] **Step 3: 生成 core 的 POM，确认 Central 元数据已经齐全**

Run: `./gradlew --console=plain --no-daemon :lightrag-core:generatePomFileForMavenPublication`

Run: `rg -n "<name>|<description>|<license>|<developer>|<scm>" lightrag-core/build/publications/maven/pom-default.xml`

Expected: PASS，`pom-default.xml` 中能看到完整的 `name`、`description`、`license`、`developer`、`scm` 节点。

- [ ] **Step 4: 生成 core 的 sources/javadoc 产物，确认 publication 基础产物完整**

Run: `./gradlew --console=plain --no-daemon :lightrag-core:sourcesJar :lightrag-core:javadocJar`

Expected: PASS，并生成：
- `lightrag-core/build/libs/lightrag-core-0.1.0-sources.jar`
- `lightrag-core/build/libs/lightrag-core-0.1.0-javadoc.jar`

- [ ] **Step 5: 提交 core 发布配置**

```bash
git add lightrag-core/build.gradle.kts
git commit -m "build: configure core publication metadata"
```

### Task 3: 为 `lightrag-spring-boot-starter` 补齐 publication，并验证依赖关系

**Files:**
- Modify: `lightrag-spring-boot-starter/build.gradle.kts`
- Test: `lightrag-spring-boot-starter/build/publications/maven/pom-default.xml`

- [ ] **Step 1: 先跑 starter 的 publication 生成，确认当前没有发布模型**

Run: `./gradlew --console=plain --no-daemon :lightrag-spring-boot-starter:generatePomFileForMavenPublication`

Expected: FAIL，提示该模块尚未声明 publication。

- [ ] **Step 2: 在 starter 模块中补齐 Java 产物、描述和 publication 配置**

```kotlin
plugins {
    id("java-library")
}

description = "Spring Boot auto-configuration for the LightRAG Java SDK."

java {
    withJavadocJar()
    withSourcesJar()
}

mavenPublishing {
    coordinates("io.github.dargoner", "lightrag-spring-boot-starter", version.toString())

    publishToMavenCentral()
    signAllPublications()

    pom {
        name.set("lightrag-spring-boot-starter")
        description.set(project.description)
        url.set("https://github.com/dargoner/lightrag-java")
        licenses {
            license {
                name.set("Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
            }
        }
        developers {
            developer {
                id.set("dargoner")
                name.set("dargoner")
            }
        }
        scm {
            url.set("https://github.com/dargoner/lightrag-java")
            connection.set("scm:git:https://github.com/dargoner/lightrag-java.git")
            developerConnection.set("scm:git:ssh://git@github.com/dargoner/lightrag-java.git")
        }
    }
}
```

- [ ] **Step 3: 生成 starter POM，并确认它带有对 core 的依赖**

Run: `./gradlew --console=plain --no-daemon :lightrag-spring-boot-starter:generatePomFileForMavenPublication`

Run: `rg -n "lightrag-core|spring-boot-autoconfigure|<scm>|<license>" lightrag-spring-boot-starter/build/publications/maven/pom-default.xml`

Expected: PASS，POM 中能看到：
- `io.github.dargoner:lightrag-core`
- `org.springframework.boot:spring-boot-autoconfigure`
- `license` / `scm` 元数据

- [ ] **Step 4: 发布到本地 Maven 仓库，确认 starter 与 core 可以一起安装**

Run: `./gradlew --console=plain --no-daemon -PreleaseVersion=0.1.0 :lightrag-core:publishToMavenLocal :lightrag-spring-boot-starter:publishToMavenLocal`

Run: `rg -n "io.github.dargoner|lightrag-core|lightrag-spring-boot-starter" ~/.m2/repository/io/github/dargoner -g 'pom-default.xml' -g '*.pom'`

Expected: PASS，本地 Maven 仓库中同时存在 core 与 starter，且 starter POM 依赖指向新的 `io.github.dargoner` 坐标。

- [ ] **Step 5: 提交 starter 发布配置**

```bash
git add lightrag-spring-boot-starter/build.gradle.kts
git commit -m "build: publish Spring Boot starter to Maven Central"
```

### Task 4: 补充发布凭据说明与仓库文档

**Files:**
- Modify: `README.md`

- [ ] **Step 1: 先确认 README 里还没有 Maven Central 发布章节**

Run: `rg -n "Maven Central|publishToMavenLocal|mavenCentralUsername|signingInMemoryKey" README.md`

Expected: FAIL，没有命中这些发布关键词。

- [ ] **Step 2: 在 README 中新增发布章节，写清 prerequisites、凭据和命令**

````md
## Publishing

To publish `lightrag-core` and `lightrag-spring-boot-starter` to Maven Central, prepare:

- a verified `io.github.dargoner` namespace on the Central Portal
- a Central Portal user token
- an exported ASCII-armored GPG private key

Add credentials to `~/.gradle/gradle.properties` or environment variables:

```properties
mavenCentralUsername=YOUR_PORTAL_TOKEN_USERNAME
mavenCentralPassword=YOUR_PORTAL_TOKEN_PASSWORD
signingInMemoryKeyId=YOUR_GPG_KEY_ID
signingInMemoryKeyPassword=YOUR_GPG_KEY_PASSWORD
signingInMemoryKey=-----BEGIN PGP PRIVATE KEY BLOCK-----
...
-----END PGP PRIVATE KEY BLOCK-----
```

Verify publication artifacts locally:

```bash
./gradlew --console=plain --no-daemon -PreleaseVersion=0.1.0 \
  :lightrag-core:publishToMavenLocal \
  :lightrag-spring-boot-starter:publishToMavenLocal
```

Publish a release to Maven Central:

```bash
./gradlew --console=plain --no-daemon -PreleaseVersion=0.1.0 \
  :lightrag-core:publishAndReleaseToMavenCentral \
  :lightrag-spring-boot-starter:publishAndReleaseToMavenCentral
```
````

- [ ] **Step 3: 重新检索 README，确认发布关键词和命令都已出现**

Run: `rg -n "Maven Central|publishAndReleaseToMavenCentral|mavenCentralUsername|signingInMemoryKey" README.md`

Expected: PASS，能命中发布章节、凭据项和两个关键命令。

- [ ] **Step 4: 提交 README 发布说明**

```bash
git add README.md
git commit -m "docs: add Maven Central publishing guide"
```

### Task 5: 做最终回归，确认测试、publication 与签名入口都可用

**Files:**
- Modify: `build.gradle.kts`
- Modify: `lightrag-core/build.gradle.kts`
- Modify: `lightrag-spring-boot-starter/build.gradle.kts`
- Modify: `README.md`

- [ ] **Step 1: 跑模块测试，确认发布改造没有带来业务回归**

Run: `./gradlew --console=plain --no-daemon :lightrag-core:test :lightrag-spring-boot-starter:test`

Expected: PASS

- [ ] **Step 2: 列出发布模块任务，确认 Central 发布入口已经就绪**

Run: `./gradlew --console=plain --no-daemon :lightrag-core:tasks --all | rg "publishAndReleaseToMavenCentral|publishToMavenLocal|generatePomFileForMavenPublication"`

Run: `./gradlew --console=plain --no-daemon :lightrag-spring-boot-starter:tasks --all | rg "publishAndReleaseToMavenCentral|publishToMavenLocal|generatePomFileForMavenPublication"`

Expected: PASS，两个模块都能看到 Central 发布、本地发布和 POM 生成任务。

- [ ] **Step 3: 用正式版本号做一次完整本地发布回归**

Run: `./gradlew --console=plain --no-daemon -PreleaseVersion=0.1.0 :lightrag-core:publishToMavenLocal :lightrag-spring-boot-starter:publishToMavenLocal`

Expected: PASS

- [ ] **Step 4: 若本地已配置真实 Portal token 和 GPG key，再执行一次正式发布前干跑**

Run: `./gradlew --console=plain --no-daemon -PreleaseVersion=0.1.0 :lightrag-core:publishAndReleaseToMavenCentral :lightrag-spring-boot-starter:publishAndReleaseToMavenCentral`

Expected: PASS，Central Portal 接受上传并返回 release 成功；若当前环境没有真实凭据，则记录为“未执行，因缺少发布密钥”。

- [ ] **Step 5: 提交最终改动**

```bash
git add build.gradle.kts gradle.properties lightrag-core/build.gradle.kts lightrag-spring-boot-starter/build.gradle.kts README.md
git commit -m "build: prepare Maven Central publishing"
```
