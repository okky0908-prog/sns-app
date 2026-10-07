package com.okimoto.sns.backend.web;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.okimoto.sns.backend.auth.JwtService;
import jakarta.servlet.RequestDispatcher;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * どんなエラーでも docs/api.md のエラー形式（status・code・message・errors）で返ることのテスト。
 *
 * <p>業務上のエラー（投稿が見つからない など）は、それぞれの API のテストで確かめる。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(ErrorHandlingTest.FailingController.class)
class ErrorHandlingTest {

  /** 想定していないエラーを起こすための、テスト用の API */
  @RestController
  static class FailingController {

    @GetMapping("/api/test/bug")
    String bug() {
      throw new IllegalStateException("内部の情報 secret-detail");
    }

    @GetMapping("/api/test/db-down")
    String dbDown() {
      throw new CannotGetJdbcConnectionException("DB に接続できない secret-detail");
    }

    /** 接続プールから接続を取れず、トランザクションを始められない */
    @GetMapping("/api/test/db-down-at-begin")
    String dbDownAtBegin() {
      throw new CannotCreateTransactionException(
          "secret-detail", new SQLTransientConnectionException("Connection is not available"));
    }

    /**
     * 実行中に DB が止まり、そのあとのロールバックも失敗した（実際に DB を止めたときに起きた形）。 ロールバックの失敗の原因は「Connection is closed」だけで、DB
     * が止まったことは元の例外の側に入っている
     */
    @GetMapping("/api/test/db-down-at-rollback")
    String dbDownAtRollback() {
      TransactionSystemException e =
          new TransactionSystemException(
              "JDBC rollback failed secret-detail", new SQLException("Connection is closed"));
      e.initApplicationException(
          new DataAccessResourceFailureException(
              "secret-detail", new SQLException("An I/O error occurred", "08006")));
      throw e;
    }
  }

  @Autowired private MockMvc mockMvc;
  @Autowired private JwtService jwtService;

  private MockHttpServletRequestBuilder loggedIn(MockHttpServletRequestBuilder request) {
    return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtService.issue(1L));
  }

  /** エラー形式の4項目がそろっていること */
  private static ResultActions expectError(ResultActions result, int status, ErrorCode code)
      throws Exception {
    return result
        .andExpect(status().is(status))
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.status").value(status))
        .andExpect(jsonPath("$.code").value(code.name()))
        .andExpect(jsonPath("$.message").value(code.message()))
        .andExpect(jsonPath("$.errors").isArray());
  }

  @Test
  void 存在しないURLは404_RESOURCE_NOT_FOUND() throws Exception {
    expectError(
        mockMvc.perform(loggedIn(get("/api/no-such-api"))), 404, ErrorCode.RESOURCE_NOT_FOUND);
  }

  @Test
  void 許可されないメソッドは405_METHOD_NOT_ALLOWED() throws Exception {
    expectError(
        mockMvc.perform(loggedIn(patch("/api/posts/1"))), 405, ErrorCode.METHOD_NOT_ALLOWED);
  }

  @Test
  void JSONでないContentTypeは415_UNSUPPORTED_MEDIA_TYPE() throws Exception {
    expectError(
        mockMvc.perform(
            loggedIn(post("/api/posts")).contentType(MediaType.TEXT_PLAIN).content("本文")),
        415,
        ErrorCode.UNSUPPORTED_MEDIA_TYPE);
  }

  @Test
  void 壊れたJSONは400_MALFORMED_REQUEST() throws Exception {
    expectError(
            mockMvc.perform(
                loggedIn(post("/api/posts"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"content\": ")),
            400,
            ErrorCode.MALFORMED_REQUEST)
        .andExpect(jsonPath("$.errors", hasSize(0)));
  }

  @Test
  void パラメータの型違いと不足は400_VALIDATION_FAILED_で項目名が分かる() throws Exception {
    expectError(
            mockMvc.perform(loggedIn(get("/api/timeline/new-count").param("since", "abc"))),
            400,
            ErrorCode.VALIDATION_FAILED)
        .andExpect(jsonPath("$.errors[0].field").value("since"));
    expectError(
            mockMvc.perform(loggedIn(get("/api/timeline/new-count"))),
            400,
            ErrorCode.VALIDATION_FAILED)
        .andExpect(jsonPath("$.errors[0].field").value("since"));
  }

  @Test
  void 想定していない例外は500_INTERNAL_ERROR_で内部の情報を返さない() throws Exception {
    expectError(mockMvc.perform(loggedIn(get("/api/test/bug"))), 500, ErrorCode.INTERNAL_ERROR)
        .andExpect(content().string(not(Matchers.containsString("secret-detail"))));
  }

  @Test
  void DBにつながらないときは503_SERVICE_UNAVAILABLE() throws Exception {
    expectError(
            mockMvc.perform(loggedIn(get("/api/test/db-down"))), 503, ErrorCode.SERVICE_UNAVAILABLE)
        .andExpect(content().string(not(Matchers.containsString("secret-detail"))));
  }

  @Test
  void DBが止まったときは失敗した時点によらず503() throws Exception {
    for (String path :
        new String[] {"/api/test/db-down-at-begin", "/api/test/db-down-at-rollback"}) {
      expectError(mockMvc.perform(loggedIn(get(path))), 503, ErrorCode.SERVICE_UNAVAILABLE)
          .andExpect(content().string(not(Matchers.containsString("secret-detail"))));
    }
  }

  @Test
  void 未ログインは404より先に401_UNAUTHENTICATED() throws Exception {
    expectError(mockMvc.perform(get("/api/no-such-api")), 401, ErrorCode.UNAUTHENTICATED);
  }

  @Test
  void フィルターなどControllerの手前で起きたエラーも同じ形式で返す() throws Exception {
    // サーブレットコンテナがエラーを /error に転送したときの状態を再現する
    expectError(
        mockMvc.perform(
            get("/error")
                .requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 500)
                .requestAttr(RequestDispatcher.ERROR_REQUEST_URI, "/api/posts")
                .requestAttr(
                    RequestDispatcher.ERROR_EXCEPTION, new IllegalStateException("filter"))),
        500,
        ErrorCode.INTERNAL_ERROR);
    expectError(
        mockMvc.perform(get("/error").requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 413)),
        413,
        ErrorCode.PAYLOAD_TOO_LARGE);
    // 直接開かれたとき
    expectError(mockMvc.perform(get("/error")), 404, ErrorCode.RESOURCE_NOT_FOUND);
  }
}
