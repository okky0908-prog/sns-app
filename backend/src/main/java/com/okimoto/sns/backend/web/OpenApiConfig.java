package com.okimoto.sns.backend.web;

import com.okimoto.sns.backend.auth.SecurityConfig;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.oas.models.tags.Tag;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springdoc.core.customizers.ParameterCustomizer;
import org.springdoc.core.customizers.PropertyCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * API 仕様書（OpenAPI）の設定（docs/api.md「API 仕様書（OpenAPI）」）。
 *
 * <p>仕様書の中身は springdoc がコントローラー・record・Javadoc から作る。ここでは、自動では載らないこのアプリの決まりごとを補う。
 *
 * <ul>
 *   <li>認証：JWT（Authorization: Bearer）を全体の既定にし、ログインなしで呼べる API だけ外す
 *   <li>エラー：共通のエラー形式（{@link ApiError}）と、code の一覧（{@link ErrorCode}）
 *   <li>独自の入力チェック（{@link TextLength}）の文字数
 *   <li>multipart の API で、テキストの項目もフォームのパートとして載せる
 *   <li>operationId・概要・タグの並びを、実行のたびに変わらない形にする（docs/openapi.yaml の差分を CI で確認するため）
 * </ul>
 */
@Configuration
public class OpenApiConfig {

  private static final String BEARER_AUTH = "bearerAuth";
  private static final String MULTIPART = "multipart/form-data";

  /** タグ（API の分類）の並び順。docs/api.md の API 一覧と同じ */
  private static final List<String> TAG_ORDER =
      List.of("認証", "投稿・タイムライン", "コメント", "いいね", "フォロー", "プロフィール・ユーザー検索");

  @Bean
  OpenAPI openApi() {
    return new OpenAPI()
        .info(
            new Info()
                .title("SNSアプリ API")
                .version("v1")
                .description(
                    """
                    X風SNSアプリの REST API。共通のルール（日時の形式・ページング・エラーの code の使い分け）と、\
                    画面との対応は docs/api.md を参照。

                    ログインが必要な API は、右上の「Authorize」にアクセストークン（ログイン・新規登録の \
                    accessToken）を入れてから試す。"""))
        // 仕様書を開いたサーバー自身に送る（ローカルなら http://localhost:8080）
        .addServersItem(new Server().url("/").description("この API を動かしているサーバー"))
        .components(
            new Components()
                .addSecuritySchemes(
                    BEARER_AUTH,
                    new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")
                        .description("アクセストークン（有効期限15分）。期限が切れたら A-04 で再発行する")))
        .addSecurityItem(new SecurityRequirement().addList(BEARER_AUTH));
  }

  /**
   * operationId を「コントローラー名＋メソッド名」にする（例：postDelete・commentDelete）。
   *
   * <p>springdoc の標準はメソッド名だけで、重なると delete_1 のような連番になる。連番は読み込み順で入れ替わることがあるので使わない。
   */
  @Bean
  OperationCustomizer operationIdCustomizer() {
    return (operation, handlerMethod) -> {
      String controller = handlerMethod.getBeanType().getSimpleName().replace("Controller", "");
      operation.setOperationId(
          StringUtils.uncapitalize(controller)
              + StringUtils.capitalize(handlerMethod.getMethod().getName()));
      return operation;
    };
  }

  /**
   * {@link TextLength} の文字数を、仕様書の minLength・maxLength に載せる（標準の @Size などは springdoc が載せる）。JSON の
   * record の項目に効く。
   */
  @Bean
  PropertyCustomizer textLengthPropertyCustomizer() {
    return (property, type) -> {
      if (type.getCtxAnnotations() != null) {
        Arrays.stream(type.getCtxAnnotations())
            .filter(TextLength.class::isInstance)
            .findFirst()
            .ifPresent(
                annotation -> {
                  TextLength length = (TextLength) annotation;
                  applyTextLength(property, length);
                  // 1文字以上なら null も通らないので、必須にする（例：新規登録の displayName）
                  if (length.min() > 0
                      && type.getParent() != null
                      && type.getPropertyName() != null) {
                    type.getParent().addRequiredItem(type.getPropertyName());
                  }
                });
      }
      return property;
    };
  }

  /** {@link TextLength} の文字数を載せる（multipart のフォームの項目に効く。例：プロフィール編集の displayName）。 */
  @Bean
  ParameterCustomizer textLengthParameterCustomizer() {
    return (parameter, methodParameter) -> {
      TextLength length = methodParameter.getParameterAnnotation(TextLength.class);
      if (parameter != null && length != null && parameter.getSchema() != null) {
        applyTextLength(parameter.getSchema(), length);
        if (length.min() > 0) {
          parameter.setRequired(true);
        }
      }
      return parameter;
    };
  }

  private static void applyTextLength(Schema<?> schema, TextLength length) {
    if (length.min() > 0) {
      schema.setMinLength(length.min());
    }
    schema.setMaxLength(length.max());
  }

  /** 認証・エラー・multipart・説明文・タグを、このアプリの決まりに合わせる（仕様書ができあがったあとに1回だけ動く）。 */
  @Bean
  OpenApiCustomizer apiConventionsCustomizer() {
    return openApi -> {
      addErrorSchemas(openApi);
      Set<String> publicPaths = Set.copyOf(SecurityConfig.PUBLIC_POST_PATHS);
      openApi
          .getPaths()
          .forEach(
              (path, item) ->
                  item.readOperationsMap()
                      .forEach(
                          (method, operation) -> {
                            boolean isPublic =
                                method == PathItem.HttpMethod.POST && publicPaths.contains(path);
                            customizeOperation(operation, isPublic);
                          }));
      requireResponseProperties(openApi);
      openApi.getComponents().getSchemas().values().forEach(OpenApiConfig::cleanSchema);
      // 一覧がないと Swagger UI は URL の順（/api/comments が先）に並べるので、明示する。
      // コントローラーを増やしたときは TAG_ORDER にも足す（足し忘れても、そのタグは最後に並ぶだけ）
      openApi.setTags(TAG_ORDER.stream().map(name -> new Tag().name(name)).toList());
    };
  }

  private static void customizeOperation(Operation operation, boolean isPublic) {
    if (isPublic) {
      // 空にすると、全体の既定（bearerAuth）を打ち消して「認証なし」になる
      operation.setSecurity(List.of());
    } else {
      operation
          .getResponses()
          .addApiResponse(
              "401",
              errorResponse("アクセストークンがない・無効・期限切れ（code: UNAUTHENTICATED）。A-04 で再発行して1回だけやり直す"));
    }
    operation
        .getResponses()
        .addApiResponse(
            "default", errorResponse("エラー。種類はステータスや文言ではなく code で見分ける（docs/api.md「エラーレスポンス」）"));
    moveQueryParametersIntoForm(operation);
    // 概要は Javadoc の最初の文（「A-11 投稿作成」など）。説明は Javadoc の全文
    String description = cleanText(operation.getDescription());
    operation.setDescription(description);
    operation.setSummary(summaryOf(description));
    if (operation.getTags() != null) {
      operation.setTags(operation.getTags().stream().distinct().toList());
    }
  }

  /**
   * レスポンスに使うスキーマ（入れ子のものと ApiError も含む）は、すべての項目を必須（required）にする。
   *
   * <p>レスポンスの JSON には、null の項目も含めて record の項目がすべて入る。必須にしないと、仕様書から作るフロントの型がすべて省略可能（{@code id?:
   * number}）になる。null になりうる項目は、record で {@code @Schema(nullable = true)}
   * を付けて示す。リクエストのスキーマは、入力チェックの注釈から springdoc が決めたままにする。
   */
  private static void requireResponseProperties(OpenAPI openApi) {
    Deque<Schema<?>> pending = new ArrayDeque<>();
    openApi.getPaths().values().stream()
        .flatMap(item -> item.readOperations().stream())
        .flatMap(operation -> operation.getResponses().values().stream())
        .filter(response -> response.getContent() != null)
        .flatMap(response -> response.getContent().values().stream())
        .forEach(media -> pending.add(media.getSchema()));
    Set<String> visited = new HashSet<>();
    while (!pending.isEmpty()) {
      Schema<?> schema = pending.pop();
      // 配列の中身・組み合わせ（null になりうる入れ子の record は oneOf になる）・Map の値の先もたどる
      if (schema.getItems() != null) {
        pending.add(schema.getItems());
      }
      Stream.of(schema.getAllOf(), schema.getOneOf(), schema.getAnyOf())
          .filter(Objects::nonNull)
          .flatMap(List::stream)
          .forEach(pending::add);
      if (schema.getAdditionalProperties() instanceof Schema<?> values) {
        pending.add(values);
      }
      if (schema.get$ref() == null) {
        continue;
      }
      String name = schema.get$ref().substring(schema.get$ref().lastIndexOf('/') + 1);
      Schema<?> component = openApi.getComponents().getSchemas().get(name);
      if (!visited.add(name) || component == null || component.getProperties() == null) {
        continue;
      }
      component.setRequired(new ArrayList<>(new TreeSet<>(component.getProperties().keySet())));
      component.getProperties().values().forEach(pending::add);
    }
  }

  private static ApiResponse errorResponse(String description) {
    return new ApiResponse()
        .description(description)
        .content(
            new Content()
                .addMediaType(
                    org.springframework.http.MediaType.APPLICATION_JSON_VALUE,
                    new MediaType().schema(new Schema<>().$ref("#/components/schemas/ApiError"))));
  }

  /**
   * エラーのレスポンス（{@link ApiError}）を仕様書に加え、code に {@link ErrorCode} の一覧を載せる。
   *
   * <p>ApiError はコントローラーの戻り値に出てこないので、springdoc は自動では載せない。
   */
  @SuppressWarnings("unchecked")
  private static void addErrorSchemas(OpenAPI openApi) {
    boolean openapi31 = openApi.getOpenapi() != null && openApi.getOpenapi().startsWith("3.1");
    Map<String, Schema> schemas = ModelConverters.getInstance(openapi31).readAll(ApiError.class);
    Schema<String> code = (Schema<String>) schemas.get("ApiError").getProperties().get("code");
    code.setEnum(Arrays.stream(ErrorCode.values()).map(ErrorCode::name).toList());
    code.setDescription(
        "エラーの種類。画面側はステータスや文言ではなく、これで見分ける\n\n| code | ステータス | 標準の文言 |\n|---|---|---|\n"
            + Arrays.stream(ErrorCode.values())
                .map(
                    error ->
                        "| `%s` | %d | %s |"
                            .formatted(error.name(), error.status().value(), error.message()))
                .collect(Collectors.joining("\n")));
    schemas.forEach(openApi.getComponents()::addSchemas);
  }

  /**
   * multipart の API で、{@code @RequestParam} のテキストの項目（例：投稿作成の content）を、クエリではなくフォームのパートとして載せる。
   *
   * <p>Spring は multipart のパートも @RequestParam で受け取れるが、springdoc はクエリパラメータとして載せてしまうため。
   *
   * <p>multipart の API では、クエリパラメータを使わないことが前提（あれば、それもフォームの項目として載ってしまう）。テキストの項目を {@code @RequestPart}
   * で受ければこの処理は要らないが、パートに Content-Type がないときの文字コードの扱いが変わり、日本語が化けるおそれがあるので使わない。
   */
  private static void moveQueryParametersIntoForm(Operation operation) {
    if (operation.getRequestBody() == null
        || operation.getRequestBody().getContent() == null
        || operation.getParameters() == null) {
      return;
    }
    MediaType form = operation.getRequestBody().getContent().get(MULTIPART);
    if (form == null || form.getSchema() == null || form.getSchema().get$ref() != null) {
      return;
    }
    List<Parameter> queries =
        operation.getParameters().stream().filter(p -> "query".equals(p.getIn())).toList();
    for (Parameter query : queries) {
      Schema<?> schema = query.getSchema();
      if (query.getDescription() != null) {
        schema.setDescription(query.getDescription());
      }
      form.getSchema().addProperty(query.getName(), schema);
      if (Boolean.TRUE.equals(query.getRequired())) {
        form.getSchema().addRequiredItem(query.getName());
      }
    }
    operation.getParameters().removeAll(queries);
    if (operation.getParameters().isEmpty()) {
      operation.setParameters(null);
    }
  }

  @SuppressWarnings("unchecked")
  private static void cleanSchema(Schema<?> schema) {
    schema.setDescription(cleanText(schema.getDescription()));
    if (schema.getProperties() != null) {
      schema.getProperties().values().forEach(OpenApiConfig::cleanSchema);
    }
  }

  /**
   * Javadoc の HTML（段落の {@code <p>}・{@code <pre>}・{@code <code>}）を、Swagger UI で読みやすい Markdown にする。
   */
  static String cleanText(String text) {
    if (text == null) {
      return null;
    }
    return text.replaceAll("\\s*<pre>\\s*", "\n\n```\n")
        .replaceAll("\\s*</pre>", "\n```")
        .replaceAll("\\s*<p>\\s*", "\n\n")
        .replaceAll("<code>(.*?)</code>", "`$1`")
        .replaceAll("\\{@code ([^}]*)}", "`$1`")
        .strip();
  }

  /**
   * 説明の最初の文（括弧の外の「。」まで）。「A-11 投稿作成（multipart/form-data）。本文と…」なら「A-11 投稿作成（multipart/form-data）」。
   *
   * <p>Javadoc は長い文を途中で折り返すので、最初の段落の行をつないでから探す。google-java-format
   * は空白の位置でだけ折り返すので、「改行＋行頭の空白」は空白1つに戻す。
   */
  static String summaryOf(String description) {
    if (description == null) {
      return null;
    }
    String firstLine = description.split("\\n\\s*\\n", 2)[0].replaceAll("\\n[ \\t]*", " ").strip();
    // 括弧の中の「。」（例：「A-13 投稿編集（本文のみ。本人だけ）。」）では区切らない
    int depth = 0;
    for (int i = 0; i < firstLine.length(); i++) {
      switch (firstLine.charAt(i)) {
        case '（' -> depth++;
        case '）' -> depth = Math.max(0, depth - 1);
        case '。' -> {
          if (depth == 0) {
            return firstLine.substring(0, i);
          }
        }
        default -> {}
      }
    }
    return firstLine;
  }
}
