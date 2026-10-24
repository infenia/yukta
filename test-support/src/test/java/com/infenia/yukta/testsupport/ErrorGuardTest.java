// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.regex.Pattern;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

// These tests provoke the very errors the guard looks for, so the guard itself must not mind them.
@ExpectedErrorLog({"ERROR guard\\.[a-z]+ - ", "dropped error: .*IllegalStateException"})
@SuppressWarnings({"PMD.CommentRequired", "PMD.GuardLogStatement"})
@NoArgsConstructor
class ErrorGuardTest {

  @Test
  void reactorProbe_seesDroppedError() {
    final ReactorProbe probe = new ReactorProbe();
    probe.start();
    Mono.error(new IllegalStateException("boom")).subscribe();
    assertThat(probe.stop()).singleElement().asString().contains("dropped error").contains("boom");
  }

  @Test
  void reactorProbe_seesNothingWhenTheErrorIsHandled() {
    final ReactorProbe probe = new ReactorProbe();
    probe.start();
    Mono.error(new IllegalStateException("boom")).subscribe(v -> {}, e -> {});
    assertThat(probe.stop()).isEmpty();
  }

  @Test
  void logbackProbe_seesErrorButNotWarning() {
    final LogbackProbe probe = new LogbackProbe();
    probe.start();
    LoggerFactory.getLogger("guard.test").warn("just a warning");
    LoggerFactory.getLogger("guard.test")
        .error("real {}", "problem", new IllegalStateException("x"));
    assertThat(probe.stop())
        .singleElement()
        .asString()
        .contains("guard.test")
        .contains("real problem")
        .contains("IllegalStateException");
  }

  @Test
  void logbackProbe_isAvailableWithLogbackAsBackend() {
    assertThat(LogbackProbe.available()).isTrue();
  }

  @Test
  void unexpected_dropsLinesThatPatternMatches() {
    final List<String> seen = List.of("ERROR a - expected one", "dropped error: surprise");
    assertThat(ErrorGuardExtension.unexpected(seen, List.of(Pattern.compile("expected one"))))
        .containsExactly("dropped error: surprise");
    assertThat(ErrorGuardExtension.unexpected(seen, List.of())).isEqualTo(seen);
  }

  @Test
  void mode_defaultsToStrictAndReadsTheOthers() {
    assertThat(ErrorGuardExtension.Mode.parse(null)).isEqualTo(ErrorGuardExtension.Mode.STRICT);
    assertThat(ErrorGuardExtension.Mode.parse(" ")).isEqualTo(ErrorGuardExtension.Mode.STRICT);
    assertThat(ErrorGuardExtension.Mode.parse("whatever"))
        .isEqualTo(ErrorGuardExtension.Mode.STRICT);
    assertThat(ErrorGuardExtension.Mode.parse("REPORT")).isEqualTo(ErrorGuardExtension.Mode.REPORT);
    assertThat(ErrorGuardExtension.Mode.parse("off")).isEqualTo(ErrorGuardExtension.Mode.OFF);
  }

  @Test
  void logbackProbe_seesErrorOfNonAdditiveLogger() {
    final var logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger("guard.private");
    logger.setAdditive(false);
    final LogbackProbe probe = new LogbackProbe();
    probe.start();
    logger.error("kept to itself");
    assertThat(probe.stop())
        .singleElement()
        .asString()
        .contains("kept to itself")
        .doesNotContain("[");
    logger.setAdditive(true);
  }
}
