plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "lightrag-java"

include("lightrag-core")
include("lightrag-spring-boot-starter")
include("lightrag-spring-boot-demo")
