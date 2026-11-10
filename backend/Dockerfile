# バックエンド（Spring Boot）のコンテナ。ECS Fargate で動かす（docs/infrastructure.md）。
# jar はローカルでビルドしてから入れる（./gradlew bootJar。infra/deploy.sh が行う）。コンテナの中ではビルドしない
#
# 起動を速くするため、AOT キャッシュ（Java 25）を使う（Issue #166。docs/infrastructure.md「ECS のサービス」）

# --- 1. jar を展開する（app.jar と lib/ のライブラリ）---
# ライブラリを jar の中に入れたままの形では、クラスをキャッシュできないため。
# 別のステージで展開し、展開したものだけを下にコピーする（元の jar をイメージに残さない）
FROM amazoncorretto:25-al2023-headless AS extract
WORKDIR /extract
COPY build/libs/backend-0.0.1-SNAPSHOT.jar app.jar
RUN java -Djarmode=tools -jar app.jar extract --destination /extract/app

FROM amazoncorretto:25-al2023-headless

# アプリを動かすユーザー（root で動かさない）
RUN dnf install -y shadow-utils && dnf clean all \
  && useradd --system --no-create-home --shell /sbin/nologin app \
  && mkdir /app && chown app /app

WORKDIR /app
USER app
COPY --from=extract --chown=app /extract/app/ /app/

# --- 2. 試しに起動して、読み込んだクラスの情報を app.aot に保存する（本番の起動で使う）---
#    イメージを作る途中には DB がないので、Flyway を止め、部品を作り終えた時点で終了する（spring.context.exit=onRefresh）。
#    値はどれもこの起動のためだけのもので、イメージには残らない（本番の値はタスク定義が渡す）。
#    GC とメモリの指定は、下の ENTRYPOINT と同じにする（違うと、キャッシュの一部が使われない）
RUN JWT_SECRET="aot-training-only-not-a-real-secret-0123456789" \
  AWS_REGION=ap-northeast-1 S3_BUCKET=aot-training S3_ENDPOINT= IMAGE_BASE_URL=https://aot-training.invalid \
  java -XX:+UseSerialGC -XX:MaxRAMPercentage=75 -XX:AOTCacheOutput=/app/app.aot \
    -Dspring.context.exit=onRefresh \
    -jar /app/app.jar --spring.profiles.active=production --spring.flyway.enabled=false

# ログに入れるリリースの版（docs/observability.md）。deploy.sh が git のコミットの短いハッシュを渡す
ARG APP_VERSION=unknown
ENV APP_VERSION=${APP_VERSION}

EXPOSE 8080
# メモリはタスクの大きさ（Terraform の backend_memory）の 75% までを Java が使う（残りは Java 自身・OS の分）。
# GC は、0.5 vCPU・1GB のタスクで Java が自動で選ぶ Serial GC を明示する（AOT キャッシュを作ったときと揃えるため）。
# AOT キャッシュが使えないとき（Java の版が変わったなど）は、警告を出してふつうに起動する
ENTRYPOINT ["java", "-XX:+UseSerialGC", "-XX:MaxRAMPercentage=75", "-XX:AOTCache=/app/app.aot", "-jar", "/app/app.jar", "--spring.profiles.active=production"]
