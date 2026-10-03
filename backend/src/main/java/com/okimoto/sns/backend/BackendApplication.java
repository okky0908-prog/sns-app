package com.okimoto.sns.backend;

import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.context.annotation.Bean;

// ログインは JWT で独自に行うため、Spring Security が用意するお試し用のユーザー（起動ログに出る自動生成パスワード）は使わない
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@ConfigurationPropertiesScan
public class BackendApplication {

  public static void main(String[] args) {
    SpringApplication.run(BackendApplication.class, args);
  }

  /** 現在時刻の取得元。テストで時刻を固定できるように Bean にしておく。 */
  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }
}
