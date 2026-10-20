package com.okimoto.sns.backend.support;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.mybatis.spring.boot.test.autoconfigure.MybatisTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Mapper（MyBatis の SQL）のテストに付ける注釈（docs/test-plan.md「テストの層」の Mapper）。
 *
 * <ul>
 *   <li>{@code @MybatisTest}：MyBatis・DB・Flyway の部分だけを読み込む（Controller や Service は読み込まないので速い）。
 *       各テストは終わるとロールバックする
 *   <li>{@code @AutoConfigureTestDatabase(replace = NONE)}：組み込みの DB に差し替えず、本物の PostgreSQL を使う
 *   <li>Testcontainers の使い捨ての PostgreSQL を使う（開発用の DB には書き込まない。H2 は使わない）
 * </ul>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@MybatisTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
public @interface MapperTest {}
