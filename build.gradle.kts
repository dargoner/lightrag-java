repositories {
    mavenCentral()
}

subprojects {
    apply(plugin = "java")

    extensions.configure<org.gradle.api.plugins.JavaPluginExtension>("java") {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(17))
        }
    }

    repositories {
        mavenCentral()
    }
}
