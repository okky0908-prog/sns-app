# バックエンド（Spring Boot）のコンテナ。ECS Fargate で動かす（docs/infrastructure.md）。
# jar はローカルでビルドしてから入れる（./gradlew bootJar。infra/deploy.sh が行う）。コンテナの中ではビルドしない
FROM amazoncorretto:25-al2023-headless

# アプリを動かすユーザー（root で動かさない）
RUN dnf install -y shadow-utils && dnf clean all \
  && useradd --system --no-create-home --shell /sbin/nologin app

WORKDIR /app
COPY build/libs/backend-0.0.1-SNAPSHOT.jar app.jar

# ログに入れるリリースの版（docs/observability.md）。deploy.sh が git のコミットの短いハッシュを渡す
ARG APP_VERSION=unknown
ENV APP_VERSION=${APP_VERSION}

USER app
EXPOSE 8080
# メモリはタスクの大きさ（Terraform の backend_memory）の 75% までを Java が使う（残りは Java 自身・OS の分）
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar", "--spring.profiles.active=production"]
