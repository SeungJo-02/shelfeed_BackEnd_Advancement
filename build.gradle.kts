plugins {
    java
    id("org.springframework.boot") version "3.4.3"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.shelfeed"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

configurations {
    compileOnly { extendsFrom(configurations.annotationProcessor.get()) }
    create("mockitoAgent")
}

repositories {
    mavenCentral()
}

dependencies {
    // Spring Boot Starters
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-client")
    implementation("org.springframework.boot:spring-boot-starter-mail")

    // JWT 0.12.x
    implementation("io.jsonwebtoken:jjwt-api:0.13.0")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.13.0")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.13.0")

    // QueryDSL 5.1 (Jakarta)
    implementation("com.querydsl:querydsl-jpa:5.1.0:jakarta")
    annotationProcessor("com.querydsl:querydsl-apt:5.1.0:jakarta")
    annotationProcessor("jakarta.annotation:jakarta.annotation-api")
    annotationProcessor("jakarta.persistence:jakarta.persistence-api")

    // SpringDoc OpenAPI (Swagger UI)
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.8.4")

    // Spring Cloud AWS 3.x (S3 - Presigned URL)
    implementation("io.awspring.cloud:spring-cloud-aws-starter-s3:3.4.2")

    // Spring Cloud AWS 3.x (SSM Parameter Store - 시크릿 관리)
    implementation("io.awspring.cloud:spring-cloud-aws-starter-parameter-store:3.4.2")

    // Prometheus 메트릭
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")

    // 분산 추적 (Zipkin 프로토콜 → Grafana Tempo)
    // perf 테스트에서 구간별 latency 측정용. 샘플링 확률은 application-perf-seed.yml에서 제어.
    implementation("io.micrometer:micrometer-tracing-bridge-brave")
    implementation("io.zipkin.reporter2:zipkin-reporter-brave")

    // Caffeine 캐시 (L1 로컬 캐시)
    implementation("com.github.ben-manes.caffeine:caffeine")

    // Elasticsearch (도서 검색 — 풀스캔 → 역인덱스)
    implementation("org.springframework.boot:spring-boot-starter-data-elasticsearch")

    // Database
    runtimeOnly("com.mysql:mysql-connector-j")

    // Dotenv (.env 파일 로드)
    implementation("me.paulschwarz:spring-dotenv:4.0.0")

    // Lombok
    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")

    // Test
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")

    // ArchUnit — MSA 분리를 위한 서비스 경계 규칙을 테스트로 강제한다.
    testImplementation("com.tngtech.archunit:archunit-junit5:1.5.0")
    testImplementation("org.testcontainers:mysql:1.21.4")
    testImplementation("org.testcontainers:junit-jupiter:1.21.4")
    add("mockitoAgent", "org.mockito:mockito-core") { isTransitive = false }
}

tasks.withType<Test> {
    useJUnitPlatform()
    jvmArgs("-javaagent:${configurations["mockitoAgent"].asPath}")
    // Docker Engine v29(Docker Desktop 4.52+, API 1.52)는 docker-java의 기본 협상 API가
    // 최소 요구치(1.44)보다 낮아 Testcontainers가 /info에서 400으로 실패한다.
    // api.version을 1.44로 고정해 로컬/CI 모두 최신 Docker에서 Testcontainers가 동작하도록 한다.
    systemProperty("api.version", "1.44")
}

// QueryDSL Q클래스 생성 경로
val querydslDir = "src/main/generated"
sourceSets["main"].java.srcDirs(querydslDir)
tasks.withType<JavaCompile> {
    options.generatedSourceOutputDirectory = file(querydslDir)
}
tasks.named("clean") {
    doLast { file(querydslDir).deleteRecursively() }
}
