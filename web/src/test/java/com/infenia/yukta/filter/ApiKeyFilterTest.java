// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.filter;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.plugin.control.ControlActor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

/** Tests for the behaviour described by {@link ApiKeyFilter}. */
@NoArgsConstructor
@SuppressWarnings({
  "PMD.LawOfDemeter",
  "PMD.AvoidDuplicateLiterals",
  "PMD.ShortVariable",
  "PMD.TooManyMethods"
})
class ApiKeyFilterTest {

  /** The passed. */
  private final AtomicBoolean passed = new AtomicBoolean();

  /** Actor seen downstream. */
  private final AtomicReference<String> actor = new AtomicReference<>();

  private MockServerWebExchange run(
      final String key, final MockServerHttpRequest.BaseBuilder<?> rq) {
    passed.set(false);
    final MockServerWebExchange exchange = MockServerWebExchange.from(rq);
    new ApiKeyFilter(key)
        .filter(
            exchange,
            _ -> {
              passed.set(true);
              return Mono.empty();
            })
        .block();
    return exchange;
  }

  /** Runs a request through a filter built from properties; returns the exchange. */
  private MockServerWebExchange runWith(
      final MockEnvironment environment, final MockServerHttpRequest.BaseBuilder<?> rq) {
    passed.set(false);
    actor.set(null);
    final MockServerWebExchange exchange = MockServerWebExchange.from(rq);
    new ApiKeyFilter(environment)
        .filter(
            exchange,
            _ ->
                Mono.deferContextual(
                    ctx -> {
                      passed.set(true);
                      actor.set(ctx.getOrDefault(ControlActor.CONTEXT_KEY, null));
                      return Mono.empty();
                    }))
        .block();
    return exchange;
  }

  private static MockEnvironment roles() {
    return new MockEnvironment()
        .withProperty("yukta.api-keys[0].name", "viewer")
        .withProperty("yukta.api-keys[0].key", "v-key")
        .withProperty("yukta.api-keys[0].role", "VIEWER")
        .withProperty("yukta.api-keys[1].name", "ops")
        .withProperty("yukta.api-keys[1].key", "o-key")
        .withProperty("yukta.api-keys[1].role", "OPERATOR")
        .withProperty("yukta.api-keys[2].name", "root")
        .withProperty("yukta.api-keys[2].key", "a-key")
        .withProperty("yukta.api-keys[2].role", "ADMIN")
        .withProperty("yukta.api-keys[3].name", "unspecified")
        .withProperty("yukta.api-keys[3].key", "u-key");
  }

  private HttpStatus statusOf(final String key, final MockServerHttpRequest.BaseBuilder<?> rq) {
    final MockServerWebExchange exchange = runWith(roles(), rq.header("X-API-Key", key));
    return passed.get() ? HttpStatus.OK : (HttpStatus) exchange.getResponse().getStatusCode();
  }

  @Test
  void testViewerMayOnlyRead() {
    assertThat(statusOf("v-key", MockServerHttpRequest.get("/api/executions")))
        .isEqualTo(HttpStatus.OK);
    assertThat(statusOf("v-key", MockServerHttpRequest.head("/api/executions")))
        .isEqualTo(HttpStatus.OK);
    assertThat(statusOf("v-key", MockServerHttpRequest.options("/api/executions")))
        .isEqualTo(HttpStatus.OK);
    assertThat(statusOf("v-key", MockServerHttpRequest.post("/api/executions/e1/pause")))
        .isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(statusOf("u-key", MockServerHttpRequest.post("/api/workflow/start")))
        .isEqualTo(HttpStatus.FORBIDDEN);
  }

  @Test
  void testOperatorControlsExecutionsButCannotDefineOrConfigure() {
    assertThat(statusOf("o-key", MockServerHttpRequest.post("/api/executions/e1/pause")))
        .isEqualTo(HttpStatus.OK);
    assertThat(statusOf("o-key", MockServerHttpRequest.post("/api/workflow/start")))
        .isEqualTo(HttpStatus.OK);
    assertThat(
            statusOf(
                "o-key", MockServerHttpRequest.delete("/api/executions/e1/nodes/n/breakpoint")))
        .isEqualTo(HttpStatus.OK);
    assertThat(statusOf("o-key", MockServerHttpRequest.post("/api/sessions")))
        .isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(statusOf("o-key", MockServerHttpRequest.post("/api/scheduler/limits")))
        .isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(
            statusOf(
                "o-key", MockServerHttpRequest.post("/api/control/workflows/w/nodes/n/command")))
        .isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(statusOf("o-key", MockServerHttpRequest.post("/api/executions/../sessions")))
        .isEqualTo(HttpStatus.FORBIDDEN);
  }

  @Test
  void testAdminMayDoEverything_andUnknownKeysAreRejected() {
    assertThat(statusOf("a-key", MockServerHttpRequest.post("/api/sessions")))
        .isEqualTo(HttpStatus.OK);
    assertThat(statusOf("nope", MockServerHttpRequest.get("/api/sessions")))
        .isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void testNamedKeysRecordTheActor_butTheSingleKeyDoesNot() {
    runWith(roles(), MockServerHttpRequest.get("/api/sessions").header("X-API-Key", "o-key"));
    assertThat(actor).hasValue("key:ops");

    runWith(
        new MockEnvironment().withProperty("yukta.api-key", "secret"),
        MockServerHttpRequest.post("/api/sessions").header("X-API-Key", "secret"));
    assertThat(passed).isTrue();
    assertThat(actor).hasValue(null);
  }

  @Test
  void testNoKeysInTheEnvironmentLeavesApiOpen() {
    runWith(new MockEnvironment(), MockServerHttpRequest.post("/api/sessions"));

    assertThat(passed).isTrue();
  }

  @Test
  void testNoKeyConfiguredLeavesApiOpen() {
    run("", MockServerHttpRequest.get("/api/sessions"));

    assertThat(passed).isTrue();
  }

  @Test
  void testMissingOrWrongKeyIsRejected() {
    final var missing = run("secret", MockServerHttpRequest.get("/api/sessions"));
    assertThat(missing.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(passed).isFalse();

    final var wrong =
        run("secret", MockServerHttpRequest.get("/api/sessions").header("X-API-Key", "nope"));
    assertThat(wrong.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void testBearerAndHeaderKeysAccepted() {
    run("secret", MockServerHttpRequest.get("/api/x").header("Authorization", "Bearer secret"));
    assertThat(passed).isTrue();

    run("secret", MockServerHttpRequest.get("/api/x").header("X-API-Key", "secret"));
    assertThat(passed).isTrue();
  }

  @Test
  void testNonApiPathsAreNotGuarded() {
    run("secret", MockServerHttpRequest.get("/actuator/health"));

    assertThat(passed).isTrue();
  }

  @Test
  void testNonBearerAuthorizationFallsBackToTheApiKeyHeader() {
    run(
        "secret",
        MockServerHttpRequest.get("/api/sessions")
            .header("Authorization", "Basic abc")
            .header("X-API-Key", "secret"));

    assertThat(passed).isTrue();
  }
}
