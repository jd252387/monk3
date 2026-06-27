# Multi-stage build for the monk3 query API.
#
# Build context is the repository root (see docker-compose.yml: build.context: .) because the
# Quarkus build needs the Gradle wrapper, settings, and the :catalog subproject. The image is
# self-contained: `docker compose build` needs no host Gradle or JDK.

# --- build stage ---------------------------------------------------------------------------------
FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace

# Copy the whole repo (trimmed by .dockerignore). settings.gradle.kts includes :catalog, :monk3 and
# :nomad, so all three project directories must be present for Gradle to configure the build, even
# though only :monk3 (+ its :catalog dependency) is built here. processResources -> syncConfig copies
# the repo-root config/ into the app resources during the build.
COPY . .

# Build the fast-jar (tests are exercised by the Gradle/CI build, not the image build).
RUN ./gradlew :monk3:build -x test --no-daemon

# --- run stage -----------------------------------------------------------------------------------
FROM eclipse-temurin:25-jre AS run
# The catalog is read from config/... resolved against the working directory; the root compose
# mounts the repo-root config/ at /work/config (read-only).
WORKDIR /work
COPY --from=build /workspace/monk3/build/quarkus-app/ /app/
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/quarkus-run.jar"]
