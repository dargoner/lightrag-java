plugins {
    id("com.vanniktech.maven.publish") version "0.34.0" apply false
}

// Task 1 仅接入根工程公共发布约定：
// 1) 统一 group/version
// 2) 将 maven-publish 插件接入可发布模块
// Central Portal 凭据、签名和完整发布参数在 Task 2/3 配置。
group = "io.github.dargoner"
version = providers.gradleProperty("releaseVersion").orElse("0.1.0-SNAPSHOT").get()

repositories {
    mavenCentral()
}

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
}

configure(
    listOf(
        project(":lightrag-core"),
        project(":lightrag-spring-boot-starter"),
    ),
) {
    // 注意：:lightrag-core 仍存在历史 publishing {} 配置，后续 Task 2/3 统一收敛。
    apply(plugin = "com.vanniktech.maven.publish")
}
