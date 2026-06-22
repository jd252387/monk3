plugins {
    java
    alias(libs.plugins.quarkus)
}

group = "com.monk3"
version = "1.0.0-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 25
}

dependencies {
    implementation(enforcedPlatform(libs.quarkus.bom))
    implementation(project(":catalog"))
    implementation(libs.quarkus.config.yaml)
    implementation(libs.quarkus.rest)
    implementation(libs.quarkus.rest.jackson)
    implementation(libs.quarkus.hibernate.validator)
    implementation(libs.quarkus.smallrye.openapi)
    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)

    testImplementation(libs.quarkus.junit5)
    testImplementation(libs.rest.assured)
    testCompileOnly(libs.lombok)
    testAnnotationProcessor(libs.lombok)
}

tasks.withType<Test>().configureEach {
    systemProperty("java.util.logging.manager", "org.jboss.logmanager.LogManager")
    // Relative catalog paths (e.g. config/mappings/*.json) resolve against the JVM working
    // directory; run from the repository root so they resolve as they did before the move.
    workingDir = rootDir
}
