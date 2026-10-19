plugins {
    `java-library`
}

description = "Optional RDF4J-based RDF/OWL/JSON-LD importer for the LightRAG Java ontology model."

// RDF4J is pinned to 5.3.2 (Java 11 bytecode, runs on the Java 17 toolchain).
// 6.x requires Java 25 and is intentionally excluded. Bump only together with a
// full re-run of the security regression suite (see Phase 2 plan §5.4).
val rdf4jVersion = "5.3.2"

val hasSigningConfig =
    (
        providers.gradleProperty("signingInMemoryKey").isPresent
        ) || (
        providers.gradleProperty("signing.secretKeyRingFile").isPresent &&
            providers.gradleProperty("signing.password").isPresent
        )

dependencies {
    api(project(":lightrag-core"))

    implementation("org.eclipse.rdf4j:rdf4j-model:$rdf4jVersion")
    implementation("org.eclipse.rdf4j:rdf4j-rio-turtle:$rdf4jVersion")
    implementation("org.eclipse.rdf4j:rdf4j-rio-rdfxml:$rdf4jVersion")
    implementation("org.eclipse.rdf4j:rdf4j-rio-jsonld:$rdf4jVersion")

    testImplementation("org.junit.jupiter:junit-jupiter:5.12.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.12.1")
    testImplementation("org.assertj:assertj-core:3.27.3")
    testImplementation("ch.qos.logback:logback-classic:1.5.18")
}

tasks.test {
    useJUnitPlatform()
}

mavenPublishing {
    coordinates("io.github.dargoner", "lightrag-rdf", version.toString())
    publishToMavenCentral()
    if (hasSigningConfig) {
        signAllPublications()
    }

    pom {
        name.set("lightrag-rdf")
        description.set(project.description)
        url.set("https://github.com/dargoner/lightrag-java")

        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                distribution.set("repo")
            }
        }

        developers {
            developer {
                id.set("dargoner")
                name.set("dargoner")
                url.set("https://github.com/dargoner")
            }
        }

        scm {
            url.set("https://github.com/dargoner/lightrag-java")
            connection.set("scm:git:https://github.com/dargoner/lightrag-java.git")
            developerConnection.set("scm:git:ssh://git@github.com/dargoner/lightrag-java.git")
        }
    }
}
