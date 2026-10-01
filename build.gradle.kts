plugins {
    java
    id("org.springframework.boot") version "3.5.3"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.gkcontas"
version = "1.0.0"
description = "Micrometer, Prometheus and Grafana: the metric types, cardinality and SLO histograms"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")

    // @Timed and @Counted are AOP advice. Without spring-boot-starter-aop on the
    // classpath the annotations compile, the application starts, and nothing is ever
    // recorded — a silent no-op that is hard to notice.
    implementation("org.springframework.boot:spring-boot-starter-aop")

    // The registry is what turns Micrometer's facade into an exposition format. Swap this
    // single artifact and the same instrumentation feeds Datadog, New Relic or OTLP: no
    // application code changes, which is the whole point of the facade.
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")

    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testCompileOnly("org.projectlombok:lombok")
    testAnnotationProcessor("org.projectlombok:lombok")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
