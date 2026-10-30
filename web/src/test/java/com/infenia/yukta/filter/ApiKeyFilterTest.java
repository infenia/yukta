// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.filter;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.plugin.control.ControlActor;
import com.infenia.yukta.security.ApiKeys;
import com.infenia.yukta.security.CallerIdentity;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

/** Tests for the behaviour described by {@link ApiKeyFilter}. */
@NoArgsConstructor
@SuppressWarnings({"PMD.AvoidDuplicateLiterals", "PMD.TooManyMethods"})
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
        .isEqualTo(HttpStatus.BAD_REQUEST);
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
    assertThat(wrong.getResponse().getBodyAsString().block())
        .contains("\"status\":401")
        .contains("X-API-Key");
  }

  @Test
  void testRequestsBeyondTheKeysRateLimitGet429() {
    System.setProperty("yukta.rate-limit-per-minute", "2");
    try {
      final ApiKeyFilter filter = new ApiKeyFilter("secret");
      final HttpStatus[] seen = new HttpStatus[3];
      for (int i = 0; i < seen.length; i++) {
        final MockServerWebExchange exchange =
            MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/x").header("X-API-Key", "secret"));
        filter.filter(exchange, _ -> Mono.empty()).block();
        seen[i] = (HttpStatus) exchange.getResponse().getStatusCode();
      }
      assertThat(seen).containsExactly(null, null, HttpStatus.TOO_MANY_REQUESTS);
    } finally {
      System.clearProperty("yukta.rate-limit-per-minute");
    }
  }

  private static InetSocketAddress from(final int host) throws UnknownHostException {
    return new InetSocketAddress(
        InetAddress.getByAddress(new byte[] {10, 0, 0, (byte) host}), 5000);
  }

  @Test
  void testFailedKeysFromOneAddress_get429_evenForGoodKeyThen() throws Exception {
    System.setProperty("yukta.auth-failures-per-minute", "2");
    try {
      final ApiKeyFilter filter = new ApiKeyFilter("secret");
      final InetSocketAddress guesser = from(1);
      final InetSocketAddress other = from(2);
      final List<HttpStatus> seen = new ArrayList<>();
      for (final var attempt :
          List.of(
              Map.entry(guesser, "bad1"),
              Map.entry(guesser, "bad2"),
              Map.entry(guesser, "secret"),
              Map.entry(other, "secret"))) {
        final MockServerWebExchange exchange =
            MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/x")
                    .remoteAddress(attempt.getKey())
                    .header("X-API-Key", attempt.getValue()));
        filter.filter(exchange, _ -> Mono.empty()).block();
        seen.add((HttpStatus) exchange.getResponse().getStatusCode());
      }
      assertThat(seen)
          .containsExactly(
              HttpStatus.UNAUTHORIZED, HttpStatus.UNAUTHORIZED, HttpStatus.TOO_MANY_REQUESTS, null);
    } finally {
      System.clearProperty("yukta.auth-failures-per-minute");
    }
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

  @Test
  void testDifferentlyWrittenPathsAreRefusedNotPassed() {
    for (final String path :
        new String[] {
          "/api;x=1/sessions", "/api/%2e%2e/api/sessions", "/api/sessions/%2Fx", "/api//sessions"
        }) {
      final var exchange =
          run("secret", MockServerHttpRequest.method(HttpMethod.GET, URI.create(path)));
      assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
      assertThat(passed).isFalse();
    }
  }

  @Test
  void testEncodedLettersAreMatchedAfterDecoding() {
    final var exchange =
        run("secret", MockServerHttpRequest.method(HttpMethod.GET, URI.create("/%61pi/sessions")));

    assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(passed).isFalse();
  }

  @Test
  void testPlainEscapeInAnIdStillWorks() {
    run(
        "secret",
        MockServerHttpRequest.method(HttpMethod.GET, URI.create("/api/sessions/my%20s"))
            .header("X-API-Key", "secret"));

    assertThat(passed).isTrue();
  }

  @Test
  void testActuatorIsClosedWithoutKeyExceptHealth() {
    assertThat(
            run("", MockServerHttpRequest.get("/actuator/loggers")).getResponse().getStatusCode())
        .isEqualTo(HttpStatus.FORBIDDEN);
    run("", MockServerHttpRequest.get("/actuator/health"));
    assertThat(passed).isTrue();
  }

  @Test
  void testActuatorNeedsKeyExceptHealth() {
    assertThat(
            run("secret", MockServerHttpRequest.get("/actuator/metrics"))
                .getResponse()
                .getStatusCode())
        .isEqualTo(HttpStatus.UNAUTHORIZED);
    run("secret", MockServerHttpRequest.get("/actuator/health"));
    assertThat(passed).isTrue();
    run("secret", MockServerHttpRequest.get("/actuator/metrics").header("X-API-Key", "secret"));
    assertThat(passed).isTrue();
  }

  @Test
  void testApiDescriptionNeedsKey() {
    for (final String path :
        List.of("/v3/api-docs", "/swagger-ui.html", "/swagger-ui/index.html")) {
      assertThat(run("secret", MockServerHttpRequest.get(path)).getResponse().getStatusCode())
          .isEqualTo(HttpStatus.UNAUTHORIZED);
      run("secret", MockServerHttpRequest.get(path).header("X-API-Key", "secret"));
      assertThat(passed).isTrue();
    }
  }

  @Test
  void testActuatorIsAdminOnly() {
    final var exchange =
        runWith(
            roles(), MockServerHttpRequest.get("/actuator/metrics").header("X-API-Key", "v-key"));

    assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
  }

  @Test
  void testHashedKeyIsAccepted() {
    final var env =
        new MockEnvironment()
            .withProperty("yukta.api-keys[0].name", "h")
            .withProperty("yukta.api-keys[0].role", "ADMIN")
            .withProperty(
                "yukta.api-keys[0].key",
                "sha256:2bb80d537b1da3e38bd30361aa855686bde0eacd7162fef6a25fe97bf527a25b");

    runWith(env, MockServerHttpRequest.get("/api/sessions").header("X-API-Key", "secret"));

    assertThat(passed).isTrue();
  }

  @Test
  void testSessionLimitedKeyReachesOnlyItsSessions() {
    final var env =
        new MockEnvironment()
            .withProperty("yukta.api-keys[0].name", "team")
            .withProperty("yukta.api-keys[0].key", "t")
            .withProperty("yukta.api-keys[0].role", "ADMIN")
            .withProperty("yukta.api-keys[0].sessions", "a");

    runWith(env, MockServerHttpRequest.get("/api/sessions/a/workflows").header("X-API-Key", "t"));
    assertThat(passed).isTrue();
    for (final String path :
        new String[] {"/api/sessions/b/workflows", "/api/sessions", "/api/executions/x"}) {
      final var exchange =
          runWith(
              env,
              MockServerHttpRequest.method(HttpMethod.GET, URI.create(path))
                  .header("X-API-Key", "t"));
      assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
  }

  @Test
  void testOtherPathsAndBareApiAndActuatorRootsAreHandled() {
    run("secret", MockServerHttpRequest.get("/index.html"));
    assertThat(passed).isTrue();
    assertThat(run("secret", MockServerHttpRequest.get("/api")).getResponse().getStatusCode())
        .isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(run("secret", MockServerHttpRequest.get("/actuator")).getResponse().getStatusCode())
        .isEqualTo(HttpStatus.UNAUTHORIZED);
    run("secret", MockServerHttpRequest.get("/actuator/health/liveness"));
    assertThat(passed).isTrue();
    assertThat(
            run("secret", MockServerHttpRequest.get("/actuator/healthz"))
                .getResponse()
                .getStatusCode())
        .isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void testSessionLimitedKeyReachesItsWorkflowPaths() {
    final var env =
        new MockEnvironment()
            .withProperty("yukta.api-keys[0].name", "team")
            .withProperty("yukta.api-keys[0].key", "t")
            .withProperty("yukta.api-keys[0].role", "ADMIN")
            .withProperty("yukta.api-keys[0].sessions", "a");

    runWith(env, MockServerHttpRequest.post("/api/workflow/a/w/stop").header("X-API-Key", "t"));
    assertThat(passed).isTrue();
    assertThat(actor.get()).isEqualTo("key:team");
    final var limitedActuator =
        runWith(env, MockServerHttpRequest.get("/actuator/metrics").header("X-API-Key", "t"));
    assertThat(limitedActuator.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
  }

  private static MockEnvironment scoped() {
    return new MockEnvironment()
        .withProperty("yukta.api-keys[0].name", "team")
        .withProperty("yukta.api-keys[0].key", "t")
        .withProperty("yukta.api-keys[0].role", "ADMIN")
        .withProperty("yukta.api-keys[0].sessions", "a");
  }

  private HttpStatus executionRoute(
      final com.infenia.yukta.service.control.gateway.ControlBusGateway gateway, final String id) {
    return executionRoute(gateway, null, id);
  }

  private HttpStatus executionRoute(
      final com.infenia.yukta.service.control.gateway.ControlBusGateway gateway,
      final com.infenia.yukta.service.log.ExecutionLogStore log,
      final String id) {
    passed.set(false);
    final var exchange =
        MockServerWebExchange.from(
            MockServerHttpRequest.post("/api/executions/" + id + "/priority")
                .header("X-API-Key", "t"));
    new ApiKeyFilter(com.infenia.yukta.security.ApiKeyAuthenticator.read(scoped()), gateway, log)
        .filter(
            exchange,
            _ -> {
              passed.set(true);
              return Mono.empty();
            })
        .block();
    return (HttpStatus) exchange.getResponse().getStatusCode();
  }

  @Test
  void testSessionLimitedKeyControlsOnlyItsOwnExecutions() {
    final var gateway =
        org.mockito.Mockito.mock(com.infenia.yukta.service.control.gateway.ControlBusGateway.class);
    final var mine = progress("a");
    final var theirs = progress("b");
    org.mockito.Mockito.when(gateway.getCurrentProgress("mine")).thenReturn(mine);
    org.mockito.Mockito.when(gateway.getCurrentProgress("theirs")).thenReturn(theirs);

    executionRoute(gateway, "mine");
    assertThat(passed).isTrue();
    assertThat(executionRoute(gateway, "theirs")).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(executionRoute(gateway, "unknown")).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(executionRoute(null, "mine")).isEqualTo(HttpStatus.FORBIDDEN);
  }

  @Test
  void testRunThatLeftTheTrackerIsFoundInTheExecutionLog() {
    final var gateway =
        org.mockito.Mockito.mock(com.infenia.yukta.service.control.gateway.ControlBusGateway.class);
    final var log = new com.infenia.yukta.service.log.InMemoryExecutionLogStore();
    log.append(
        com.infenia.yukta.service.log.ExecutionLogEvent.started("old-mine", "a", "w", "v", null));
    log.append(
        com.infenia.yukta.service.log.ExecutionLogEvent.started("old-theirs", "b", "w", "v", null));

    executionRoute(gateway, log, "old-mine");
    assertThat(passed).isTrue();
    assertThat(executionRoute(gateway, log, "old-theirs")).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(executionRoute(gateway, log, "never-existed")).isEqualTo(HttpStatus.FORBIDDEN);
  }

  private static com.infenia.yukta.model.execution.WorkflowProgress progress(final String session) {
    final var progress =
        org.mockito.Mockito.mock(com.infenia.yukta.model.execution.WorkflowProgress.class);
    org.mockito.Mockito.when(progress.sessionId()).thenReturn(session);
    return progress;
  }

  @Test
  void testResolvedKeyReachesTheRequestAndSessionLimitedKeyMayAskWhoItIs() {
    final MockEnvironment env =
        new MockEnvironment()
            .withProperty("yukta.api-keys[0].name", "scoped")
            .withProperty("yukta.api-keys[0].key", "s-key")
            .withProperty("yukta.api-keys[0].role", "ADMIN")
            .withProperty("yukta.api-keys[0].sessions", "team-a");
    final AtomicReference<Object> principal = new AtomicReference<>();
    final MockServerWebExchange me =
        MockServerWebExchange.from(
            MockServerHttpRequest.get("/api/me").header("X-API-Key", "s-key"));
    new ApiKeyFilter(env)
        .filter(
            me,
            _ ->
                Mono.deferContextual(
                    ctx -> {
                      principal.set(ctx.getOrDefault(CallerIdentity.CONTEXT_KEY, null));
                      return Mono.empty();
                    }))
        .block();

    assertThat(principal.get()).isInstanceOf(ApiKeys.Match.class);
    assertThat(((ApiKeys.Match) principal.get()).name()).isEqualTo("scoped");
    assertThat(
            statusOf2(env, MockServerHttpRequest.get("/api/plugins").header("X-API-Key", "s-key")))
        .isEqualTo(HttpStatus.FORBIDDEN);
  }

  private HttpStatus statusOf2(
      final MockEnvironment env, final MockServerHttpRequest.BaseBuilder<?> rq) {
    final MockServerWebExchange exchange = runWith(env, rq);
    return passed.get() ? HttpStatus.OK : (HttpStatus) exchange.getResponse().getStatusCode();
  }
}
