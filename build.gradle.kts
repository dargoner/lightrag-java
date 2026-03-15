plugins {
    java
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter:5.12.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.12.1")
    testImplementation("org.assertj:assertj-core:3.27.3")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.postgresql:postgresql:42.7.10")
    implementation("com.zaxxer:HikariCP:7.0.2")
    implementation("com.pgvector:pgvector:0.1.6")
    implementation("org.neo4j.driver:neo4j-java-driver:5.28.5")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.testcontainers:junit-jupiter:1.21.4")
    testImplementation("org.testcontainers:postgresql:1.21.4")
    testImplementation("org.testcontainers:neo4j:1.21.4")
}

tasks.test {
    useJUnitPlatform()
}
