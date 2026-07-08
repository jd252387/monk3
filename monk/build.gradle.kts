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
    implementation("io.quarkiverse.helm:quarkus-helm:1.4.1")
    implementation(libs.quarkus.smallrye.health)
    implementation(libs.quarkus.smallrye.fault.tolerance)
    implementation(libs.quarkus.micrometer.registry.prometheus)
    implementation(libs.quarkus.cache)
    implementation(libs.quarkus.openshift)
    implementation(libs.quarkus.kubernetes.config)
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

// Generate the monk-config ConfigMap (src/main/kubernetes/openshift.yml) from application.yaml so
// the two can't drift. Quarkus reads src/main/kubernetes during augmentation and quarkus-helm
// templates it into the chart; wiring this before quarkusGenerateCode* guarantees it runs first.
val genConfigMap by tasks.registering {
    val appYaml = layout.projectDirectory.file("src/main/resources/application.yaml")
    val out = layout.projectDirectory.file("src/main/kubernetes/openshift.yml")
    inputs.file(appYaml)
    outputs.file(out)
    doLast {
        val body = appYaml.asFile.readText().trimEnd('\n').lines()
            .joinToString("\n") { if (it.isBlank()) "" else "    $it" }
        val header = """
            # GENERATED from src/main/resources/application.yaml by :monk:genConfigMap — do not edit.
            # Read at runtime by quarkus-kubernetes-config (%prod: config-maps: monk-config).
            apiVersion: v1
            kind: ConfigMap
            metadata:
              name: monk-config
            data:
              application.yaml: |
        """.trimIndent()
        out.asFile.parentFile.mkdirs()
        out.asFile.writeText("$header\n$body\n")
    }
}

tasks.matching { it.name.startsWith("quarkusGenerateCode") }.configureEach { dependsOn(genConfigMap) }