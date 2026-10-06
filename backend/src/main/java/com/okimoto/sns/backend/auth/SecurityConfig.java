package com.okimoto.sns.backend.auth;

import com.okimoto.sns.backend.web.ApiError;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
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
                auth.requestMatchers(
                        HttpMethod.POST,
                        "/api/auth/signup",
                        "/api/auth/login",
                        "/api/auth/refresh",
                        "/api/auth/logout")
                    .permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/health")
                    .permitAll()
                    .requestMatchers("/error")
                    .permitAll()
                    // SSE（タイムラインの通知）の非同期の続き。ログインは最初の接続のときに確認済み
                    .dispatcherTypeMatchers(DispatcherType.ASYNC)
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .exceptionHandling(
            handling ->
                handling
                    .authenticationEntryPoint(
                        (request, response, e) ->
                            writeError(
                                response, objectMapper, HttpStatus.UNAUTHORIZED, "ログインが必要です"))
                    .accessDeniedHandler(
                        (request, response, e) ->
                            writeError(
                                response, objectMapper, HttpStatus.FORBIDDEN, "この操作は実行できません")))
        .addFilterBefore(
            new JwtAuthenticationFilter(jwtService), UsernamePasswordAuthenticationFilter.class);
    return http.build();
  }

  @Bean
  PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
  }

  private static void writeError(
      HttpServletResponse response, ObjectMapper objectMapper, HttpStatus status, String message)
      throws IOException {
    response.setStatus(status.value());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding("UTF-8");
    response
        .getWriter()
        .write(objectMapper.writeValueAsString(ApiError.of(status.value(), message)));
  }
}
