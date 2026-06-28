import io.quarkus.gradle.tasks.QuarkusDev

plugins {
    java
    alias(libs.plugins.quarkus)
}

group = "com.monk"
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
    implementation(libs.quarkus.container.image.jib)
    implementation(libs.quarkus.quinoa)
    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)

    testImplementation(libs.quarkus.junit5)
    testImplementation(libs.rest.assured)
    testCompileOnly(libs.lombok)
    testAnnotationProcessor(libs.lombok)
}

tasks.withType<Test>().configureEach {
    systemProperty("java.util.logging.manager", "org.jboss.logmanager.LogManager")
    // Tests are standalone: SearchBackendTestResource bundles its own catalog/backends/mappings
    // fixtures (src/test/resources/config/mappings) and points the catalog at them by absolute
    // path, so the suite reads nothing from the repo-root config/ and needs no special working dir.
}

tasks.withType<QuarkusDev>().configureEach {
    // Dev mode reads the shared config/ at the repository root via relative paths (e.g.
    // ./config/catalog.json); run from the root so those paths resolve, like the Test tasks above.
    setWorkingDir(rootDir.absolutePath)
}