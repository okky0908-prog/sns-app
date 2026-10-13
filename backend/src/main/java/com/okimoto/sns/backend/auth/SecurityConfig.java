package com.okimoto.sns.backend.auth;

import com.okimoto.sns.backend.web.ApiError;
import com.okimoto.sns.backend.web.ErrorCode;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import tools.jackson.databind.ObjectMapper;

/**
 * 認証・認可の設定。
 *
 * <ul>
 *   <li>セッションは使わず、リクエストごとに JWT で本人確認する
 *   <li>新規登録・ログイン・再発行・ログアウト・ヘルスチェック以外の API はログイン必須（再発行・ログアウトはアクセストークンの代わりにリフレッシュトークンの Cookie を使う）
 *   <li>未ログインは 401、権限なしは 403 を、docs/api.md のエラー形式（JSON）で返す
 * </ul>
 */
@Configuration
public class SecurityConfig {

  /** ログインなしで呼べる API（すべて POST）。API 仕様書でも、この API だけ認証なしとして載せる（OpenApiConfig） */
  public static final String[] PUBLIC_POST_PATHS = {
    "/api/auth/signup", "/api/auth/login", "/api/auth/refresh", "/api/auth/logout"
  };

  /** API 仕様書と Swagger UI。本番では springdoc の設定（SPRINGDOC_ENABLED=false）で URL ごと無くなり、ここを開けても 404 になる */
  private static final String[] API_DOCS_PATHS = {
    "/v3/api-docs", "/v3/api-docs/**", "/v3/api-docs.yaml", "/swagger-ui.html", "/swagger-ui/**"
  };

  @Bean
  SecurityFilterChain securityFilterChain(
      HttpSecurity http, JwtService jwtService, ObjectMapper objectMapper) throws Exception {
    http.csrf(AbstractHttpConfigurer::disable)
        .formLogin(AbstractHttpConfigurer::disable)
        .httpBasic(AbstractHttpConfigurer::disable)
        .logout(AbstractHttpConfigurer::disable)
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers(HttpMethod.POST, PUBLIC_POST_PATHS)
                    .permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/health")
                    .permitAll()
                    .requestMatchers(HttpMethod.GET, API_DOCS_PATHS)
                    .permitAll()
                    .requestMatchers("/error")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .exceptionHandling(
            handling ->
                handling
                    .authenticationEntryPoint(
                        (request, response, e) ->
                            writeError(response, objectMapper, ErrorCode.UNAUTHENTICATED))
                    .accessDeniedHandler(
                        (request, response, e) ->
                            writeError(response, objectMapper, ErrorCode.FORBIDDEN)))
        .addFilterBefore(
            new JwtAuthenticationFilter(jwtService), UsernamePasswordAuthenticationFilter.class);
    return http.build();
  }

  @Bean
  PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
  }

  private static void writeError(
      HttpServletResponse response, ObjectMapper objectMapper, ErrorCode code) throws IOException {
    response.setStatus(code.status().value());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding("UTF-8");
    response.getWriter().write(objectMapper.writeValueAsString(ApiError.of(code)));
  }
}
