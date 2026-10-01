# Runtime only: the jar is built on the host with ./gradlew bootJar.
#
# A multi-stage build that compiles inside Docker would be more self-contained and would
# also download the whole Gradle distribution and dependency tree on every cold build.
# For a demonstration stack that is minutes of waiting to prove nothing about metrics.
FROM eclipse-temurin:21-jre

WORKDIR /app
COPY build/libs/metrics-observability-1.0.0.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
