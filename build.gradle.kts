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

tasks.withType<JavaCompile> {
    options.release.set(25)
}

dependencies {
    implementation(enforcedPlatform(libs.quarkus.bom))
    implementation(libs.quarkus.config.yaml)
    implementation(libs.quarkus.rest)
    implementation(libs.quarkus.rest.jackson)
    implementation(libs.quarkus.hibernate.validator)
    implementation(libs.quarkus.smallrye.openapi)
    implementation(libs.jsonschema.generator)
    implementation(libs.jsonschema.module.jackson)
    implementation(libs.jsonschema.module.jakarta.validation)
    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)

    testImplementation(libs.quarkus.junit5)
    testImplementation(libs.rest.assured)
    testCompileOnly(libs.lombok)
    testAnnotationProcessor(libs.lombok)
}

val copySearchQuerySchema by tasks.registering(Copy::class) {
    from(layout.projectDirectory.file("search-query-dsl.schema.json"))
    into(layout.buildDirectory.dir("generated/resources/search-query-schema"))
}

sourceSets {
    main {
        resources.srcDir(copySearchQuerySchema)
    }
}

tasks.withType<Test> {
    systemProperty("java.util.logging.manager", "org.jboss.logmanager.LogManager")
}
