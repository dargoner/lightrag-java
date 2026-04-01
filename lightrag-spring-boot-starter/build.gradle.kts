plugins {
    id("java-library")
}

description = "Spring Boot starter for integrating LightRAG Java into Spring Boot applications."

val springBootVersion = "3.3.5"
val hasSigningConfig =
    (
        providers.gradleProperty("signingInMemoryKey").isPresent
        ) || (
        providers.gradleProperty("signing.secretKeyRingFile").isPresent &&
            providers.gradleProperty("signing.password").isPresent
        )

mavenPublishing {
    coordinates("io.github.dargoner", "lightrag-spring-boot-starter", version.toString())

    publishToMavenCentral()
    if (hasSigningConfig) {
        signAllPublications()
    }

    pom {
        name.set("lightrag-spring-boot-starter")
        description.set(project.description)
        url.set("https://github.com/dargoner/lightrag-java")

        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
            }
        }

        developers {
            developer {
                id.set("dargoner")
                name.set("dargoner")
                email.set("dargoner@gmail.com")
            }
        }

        scm {
            connection.set("scm:git:https://github.com/dargoner/lightrag-java.git")
            developerConnection.set("scm:git:ssh://git@github.com/dargoner/lightrag-java.git")
            url.set("https://github.com/dargoner/lightrag-java")
        }
    }
}

dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:$springBootVersion"))
    testImplementation(platform("org.springframework.boot:spring-boot-dependencies:$springBootVersion"))
    api(project(":lightrag-core"))
    implementation("org.springframework.boot:spring-boot-autoconfigure")
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor:$springBootVersion")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.testcontainers:junit-jupiter:1.21.4")
    testImplementation("org.testcontainers:testcontainers:1.21.4")
    testImplementation("org.testcontainers:mysql:1.21.4")
    testImplementation("org.testcontainers:neo4j:1.21.4")
}

tasks.test {
    useJUnitPlatform()
}
