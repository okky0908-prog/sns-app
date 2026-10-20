// ビルド用プラグイン（Spring Boot の Gradle プラグイン 4.1.1）が中で使う版にも脆弱性があるため、直った版を指定する（Issue #76）。
// ビルドのときにだけ動き、アプリの jar には入らないので、アプリ側の版（下の extra）とそろえる必要はない。
// 版は Dependabot に最新の修正版を追わせる（Issue #85）。上げたときは、bootJar で jar を作って起動できることを確かめる。
// Spring Boot を次に上げるときに、プラグインの使う版がこれ以上になっていれば、この指定は消す（docs/tech-stack.md）
buildscript {
    dependencies {
        constraints {
            classpath("tools.jackson.core:jackson-core:3.2.3")
            classpath("tools.jackson.core:jackson-databind:3.2.3")
            classpath("org.apache.commons:commons-lang3:3.21.0")
        }
    }
}

plugins {
    java
    jacoco
    id("org.springframework.boot") version "4.1.1"
    id("io.spring.dependency-management") version "1.1.7"
    id("com.diffplug.spotless") version "8.10.4"
}

group = "com.okimoto.sns"
version = "0.0.1-SNAPSHOT"
description = "X風SNSアプリ バックエンド"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

val mybatisVersion = "4.1.0"
val jjwtVersion = "0.13.0"
val awsSdkVersion = "2.55.14"
val springdocVersion = "3.1.1"
val therapiVersion = "0.15.0"

// Spring Boot 4.1.1 が使う版には脆弱性があるため、直った版を個別に指定する（Dependabot alerts。Issue #63・#70）。
// Spring Boot を次に上げるときに、Spring Boot の版がこれ以上になっていれば、この3行は消す（docs/tech-stack.md）
extra["tomcat.version"] = "11.0.26"
extra["jackson-bom.version"] = "3.1.7"
// Jackson 2 は springdoc（API 仕様書の生成。内部の swagger-core）だけが使う。Spring Boot 本体は Jackson 3
extra["jackson-2-bom.version"] = "2.21.7"

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.mybatis.spring.boot:mybatis-spring-boot-starter:$mybatisVersion")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("io.jsonwebtoken:jjwt-api:$jjwtVersion")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:$jjwtVersion")
    // JSON の処理には Gson を使う（jjwt-jackson は Jackson 2 に依存し、Spring Boot 4 の Jackson 3 と混ざるため）
    runtimeOnly("io.jsonwebtoken:jjwt-gson:$jjwtVersion")
    // 画像の保存先（S3）。同期の S3 クライアントだけを使うので、非同期用の Netty は入れない
    implementation(platform("software.amazon.awssdk:bom:$awsSdkVersion"))
    implementation("software.amazon.awssdk:s3") {
        exclude(group = "software.amazon.awssdk", module = "netty-nio-client")
    }
    // API 仕様書（OpenAPI）の自動生成と Swagger UI（docs/api.md「API 仕様書（OpenAPI）」）。Spring Boot 4 には v3 系が対応する
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:$springdocVersion")
    // コントローラー・record の Javadoc を、仕様書の説明文として使う（コンパイル時に Javadoc を残しておく）
    implementation("com.github.therapi:therapi-runtime-javadoc:$therapiVersion")
    annotationProcessor("com.github.therapi:therapi-runtime-javadoc-scribe:$therapiVersion")
    runtimeOnly("org.postgresql:postgresql")
    testImplementation("org.springframework.boot:spring-boot-starter-actuator-test")
    testImplementation("org.springframework.boot:spring-boot-starter-flyway-test")
    testImplementation("org.springframework.boot:spring-boot-starter-security-test")
    testImplementation("org.springframework.boot:spring-boot-starter-validation-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    // テストは、使い捨ての PostgreSQL と LocalStack のコンテナで動かす（開発用の DB に書き込まない。Issue #89）。
    // 版は Spring Boot が管理する
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.testcontainers:testcontainers-localstack")
    testImplementation("org.mybatis.spring.boot:mybatis-spring-boot-starter-test:$mybatisVersion")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Javadoc を残す対象（therapi-runtime-javadoc）。指定しないと @RetainJavadoc を付けたクラスだけになる
tasks.withType<JavaCompile> {
    options.compilerArgs.add("-Ajavadoc.packages=com.okimoto.sns.backend")
}

tasks.withType<Test> {
    useJUnitPlatform()
    // OpenApiSpecTest が API 仕様書を書き出す。消したときにもテストをやり直すよう、出力として登録する
    outputs.file("../docs/openapi.yaml")
    // テストのあとに、カバレッジ（どの行・分岐をテストが通ったか）のレポートを作る
    finalizedBy(tasks.jacocoTestReport)
}

// テストのカバレッジ（docs/test-plan.md「ホワイトボックステスト」）。Java 25 のクラスを読めるよう、0.8.14 以上を使う
jacoco {
    toolVersion = "0.8.15"
}

// レポートは build/reports/jacoco/test/ に作る（html：ブラウザで見る、xml・csv：CI や集計に使う）
tasks.jacocoTestReport {
    reports {
        html.required = true
        xml.required = true
        csv.required = true
    }
}

spotless {
    java {
        googleJavaFormat()
        removeUnusedImports()
        importOrder()
        trimTrailingWhitespace()
        endWithNewline()
    }
    kotlinGradle {
        target("*.gradle.kts")
        ktlint()
    }
}
