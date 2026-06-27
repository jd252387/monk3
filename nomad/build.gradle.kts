import io.quarkus.gradle.tasks.QuarkusDev

plugins {
    java
    alias(libs.plugins.quarkus)
}

group = "com.example"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

dependencies {
    implementation(enforcedPlatform("org.eclipse.jetty:jetty-bom:10.0.22"))
    implementation(enforcedPlatform(libs.quarkus.bom))
    implementation(platform(libs.quarkus.camel.bom))

    // Shared configuration catalog (mappings, backends, datasources, hot reload).
    implementation(project(":catalog"))

    implementation("io.quarkus:quarkus-arc")
    implementation("io.quarkus:quarkus-mutiny")
    implementation("io.smallrye.reactive:mutiny-zero-flow-adapters")
    implementation("io.quarkus:quarkus-config-yaml")
    implementation("io.quarkus:quarkus-jackson")
    implementation("io.quarkus:quarkus-hibernate-validator")
    implementation("io.quarkus:quarkus-container-image-jib")

    implementation("org.apache.camel.quarkus:camel-quarkus-kafka")
    implementation("org.apache.camel.quarkus:camel-quarkus-reactive-streams")
    implementation("org.apache.camel.quarkus:camel-quarkus-elasticsearch")
    implementation("org.apache.camel.quarkus:camel-quarkus-solr")
    implementation("org.apache.camel.quarkus:camel-quarkus-aws2-s3")
    implementation("org.apache.camel.quarkus:camel-quarkus-http")
    implementation("org.apache.camel.quarkus:camel-quarkus-jackson")
    implementation("org.apache.camel.quarkus:camel-quarkus-mongodb")
    // NOTE: camel-quarkus-hbase has no Quarkus 3.x / Camel 4 release (the HBase component was removed in
    // Camel 4), so the HBase datasource fetcher is not supported on this platform and has been dropped.
    implementation("org.apache.camel:camel-endpointdsl")

    implementation("org.apache.solr:solr-solrj:9.9.0")

    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")
    implementation("net.thisptr:jackson-jq:1.0.0")

    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)

    testImplementation("io.quarkus:quarkus-junit5")
    testImplementation("io.rest-assured:rest-assured")
    testCompileOnly(libs.lombok)
    testAnnotationProcessor(libs.lombok)
}

configurations.all {
    resolutionStrategy.dependencySubstitution {
        substitute(module("asm:asm")).using(module("org.ow2.asm:asm:9.7.1"))
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 25
    options.compilerArgs.add("-parameters")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    systemProperty("java.util.logging.manager", "org.jboss.logmanager.LogManager")
    // Relative catalog paths (config/**) resolve against the JVM working directory; run from the
    // repository root so the shared config files resolve as the indexer expects at runtime.
    workingDir = rootDir
}

tasks.withType<QuarkusDev>().configureEach {
    // Dev mode reads the shared config/ at the repository root via relative paths; run from the
    // root so those paths resolve, mirroring the Test tasks above.
    setWorkingDir(rootDir.absolutePath)
}