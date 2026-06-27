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
    implementation(libs.quarkus.container.image.jib)
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

// --- Local run support ---------------------------------------------------------------------------
// The shared configuration catalog lives at the repository root (config/) and is consumed by
// :catalog, which resolves relative paths like "config/catalog.json" against the JVM working
// directory. For local runs we copy the shared config into this project's resources and point the
// dev/run working directory at them, so `quarkusDev` / `quarkusRun` find the catalog with no manual
// copy step. The synced copy is build output (git-ignored); edit the originals under <root>/config.
val syncConfig by tasks.registering(Sync::class) {
    description = "Copies the shared repository-root config/ into this project's resources for local runs."
    from(rootProject.layout.projectDirectory.dir("config")) {
        exclude("old/**")
    }
    into(layout.projectDirectory.dir("src/main/resources/config"))
}

tasks.named("processResources") {
    dependsOn(syncConfig)
}

tasks.named<io.quarkus.gradle.tasks.QuarkusDev>("quarkusDev") {
    dependsOn(syncConfig)
    workingDirectory.set(layout.projectDirectory.dir("src/main/resources").asFile)
}

tasks.named<io.quarkus.gradle.tasks.QuarkusRun>("quarkusRun") {
    dependsOn(syncConfig)
    workingDirectory.set(layout.projectDirectory.dir("src/main/resources").asFile)
}
