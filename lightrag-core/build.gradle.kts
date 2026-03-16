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
    implementation("org.testcontainers:postgresql:1.21.4")
    implementation("org.testcontainers:neo4j:1.21.4")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.testcontainers:junit-jupiter:1.21.4")
}

tasks.test {
    useJUnitPlatform()
}

tasks.register<JavaExec>("runRagasQuery") {
    group = "evaluation"
    description = "Runs a single LightRAG query for RAGAS evaluation."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("io.github.lightragjava.evaluation.RagasEvaluationCli")
}

tasks.register<JavaExec>("runRagasBatchEval") {
    group = "evaluation"
    description = "Runs the full LightRAG evaluation dataset and returns answers plus contexts."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("io.github.lightragjava.evaluation.RagasBatchEvaluationCli")
}
