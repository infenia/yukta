// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.message.Message;
import com.infenia.yukta.plugin.exception.WorkflowExecutionException;
import com.infenia.yukta.util.VariableResolver;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@SuppressWarnings({
  "PMD.TooManyMethods",
  "PMD.CommentRequired",
  "PMD.AvoidDuplicateLiterals",
  "PMD.LawOfDemeter",
  "unchecked"
})
@NoArgsConstructor
class HttpRequestPluginTest {

  private final HttpRequestGateway gateway = mock(HttpRequestGateway.class);
  private final VariableResolver resolver = mock(VariableResolver.class);
  private HttpRequestPlugin plugin;

  @BeforeEach
  void setUp() {
    plugin = new HttpRequestPlugin(gateway, resolver);
    when(resolver.resolve(any(), any()))
        .thenAnswer(
            inv -> {
              final Object arg = inv.getArgument(0);
              return Mono.justOrEmpty(arg);
            });
  }

  private static Message<?> input() {
    return DefaultMessage.create(UUID.randomUUID(), "in");
  }

  private static HttpRequestResult okResult(final String body, final boolean json) {
    return new HttpRequestResult(200, Map.of("h", "v"), json, body);
  }

  private Flux<Message<?>> run(final Map<String, Object> config) {
    return plugin.process(Flux.just(input()), config);
  }

  @Test
  void metadataDescribesPlugin() {
    assertThat(plugin.getType()).isEqualTo("HTTP_REQUEST");
    assertThat(plugin.describe().description()).isNotBlank();
    assertThat(plugin.describe().usagePattern()).contains("url");
  }

  @Test
  void outputPortsFollowRouteByStatus() {
    assertThat(plugin.getOutputPorts(Map.of())).containsExactly("default");
    assertThat(plugin.getOutputPorts(Map.of("routeByStatus", true)))
        .containsExactly("success", "failure");
  }

  @Test
  void successParsesJsonBodyAndResolvesRequest() {
    when(gateway.execute(any())).thenReturn(Mono.just(okResult("{\"a\":1}", true)));
    final Map<String, Object> config =
        Map.of(
            "url",
            "http://x/y",
            "method",
            "post",
            "headers",
            Map.of("A", "b"),
            "body",
            Map.of("k", "v"),
            "timeout",
            5);

    StepVerifier.create(run(config))
        .assertNext(
            m -> {
              final Map<String, Object> payload = (Map<String, Object>) m.getPayload();
              assertThat(payload)
                  .containsEntry("status", 200)
                  .containsEntry("success", true)
                  .containsEntry("body", Map.of("a", 1));
              assertThat(m.getMetadata()).containsEntry("status", 200);
              assertThat(m.getSourcePort()).isEqualTo("default");
            })
        .verifyComplete();

    final ArgumentCaptor<HttpRequestSpec> spec = ArgumentCaptor.forClass(HttpRequestSpec.class);
    org.mockito.Mockito.verify(gateway).execute(spec.capture());
    assertThat(spec.getValue().method()).isEqualTo("POST");
    assertThat(spec.getValue().url()).isEqualTo("http://x/y");
    assertThat(spec.getValue().headers()).containsEntry("A", "b");
    assertThat(spec.getValue().body()).isEqualTo(Map.of("k", "v"));
    assertThat(spec.getValue().timeout()).isEqualTo(Duration.ofSeconds(5));
  }

  @Test
  void textBodyAndDefaultsWithoutHeadersOrBody() {
    when(gateway.execute(any())).thenReturn(Mono.just(okResult("plain", false)));

    StepVerifier.create(run(Map.of("url", "http://x")))
        .assertNext(
            m -> assertThat((Map<String, Object>) m.getPayload()).containsEntry("body", "plain"))
        .verifyComplete();
    final ArgumentCaptor<HttpRequestSpec> spec = ArgumentCaptor.forClass(HttpRequestSpec.class);
    org.mockito.Mockito.verify(gateway).execute(spec.capture());
    assertThat(spec.getValue().method()).isEqualTo("GET");
    assertThat(spec.getValue().body()).isNull();
    assertThat(spec.getValue().timeout()).isEqualTo(Duration.ofSeconds(30));
  }

  @Test
  void invalidJsonBodyFallsBackToText() {
    when(gateway.execute(any())).thenReturn(Mono.just(okResult("{oops", true)));

    StepVerifier.create(run(Map.of("url", "http://x")))
        .assertNext(
            m -> assertThat((Map<String, Object>) m.getPayload()).containsEntry("body", "{oops"))
        .verifyComplete();
  }

  @Test
  void errorModeFailsOnNon2xx() {
    when(gateway.execute(any()))
        .thenReturn(Mono.just(new HttpRequestResult(500, Map.of(), false, "boom")));

    StepVerifier.create(run(Map.of("url", "http://x")))
        .expectErrorSatisfies(
            e ->
                assertThat(e)
                    .isInstanceOf(WorkflowExecutionException.class)
                    .hasMessageContaining("500"))
        .verify();
  }

  @Test
  void errorModePropagatesTransportErrors() {
    when(gateway.execute(any())).thenReturn(Mono.error(new IllegalStateException("down")));

    StepVerifier.create(run(Map.of("url", "http://x")))
        .expectError(IllegalStateException.class)
        .verify();
  }

  @Test
  void continueModeRoutesFailureAndSuccess() {
    final Map<String, Object> config =
        Map.of("url", "http://x", "failureMode", "continue", "routeByStatus", true);

    when(gateway.execute(any()))
        .thenReturn(Mono.just(new HttpRequestResult(404, Map.of(), false, "nf")));
    StepVerifier.create(run(config))
        .assertNext(m -> assertThat(m.getSourcePort()).isEqualTo("failure"))
        .verifyComplete();

    when(gateway.execute(any())).thenReturn(Mono.just(okResult("ok", false)));
    StepVerifier.create(run(config))
        .assertNext(m -> assertThat(m.getSourcePort()).isEqualTo("success"))
        .verifyComplete();
  }

  @Test
  void continueModeTurnsTransportErrorIntoStatusZero() {
    when(gateway.execute(any())).thenReturn(Mono.error(new IllegalStateException("down")));

    StepVerifier.create(run(Map.of("url", "http://x", "failureMode", "CONTINUE")))
        .assertNext(
            m -> {
              final Map<String, Object> payload = (Map<String, Object>) m.getPayload();
              assertThat(payload).containsEntry("status", 0).containsEntry("success", false);
              assertThat((String) payload.get("body")).contains("down");
            })
        .verifyComplete();
  }

  @Test
  void nonPositiveTimeoutFailsAtRuntime() {
    StepVerifier.create(run(Map.of("url", "http://x", "timeout", 0)))
        .expectError(IllegalArgumentException.class)
        .verify();
  }

  @Test
  void validateConfigAcceptsValid() {
    StepVerifier.create(plugin.validateConfig(Map.of("url", "u", "timeout", 3))).verifyComplete();
    StepVerifier.create(
            plugin.validateConfig(
                Map.of("url", "u", "failureMode", "CONTINUE", "routeByStatus", true)))
        .verifyComplete();
  }

  @Test
  void validateConfigRejectsInvalid() {
    for (final Map<String, Object> bad :
        List.<Map<String, Object>>of(
            Map.of(),
            Map.of("url", "u", "timeout", 0),
            Map.of("url", "u", "routeByStatus", true),
            Map.of("url", "u", "failureMode", "nope"))) {
      StepVerifier.create(plugin.validateConfig(bad))
          .expectError(IllegalArgumentException.class)
          .verify();
    }
  }
}
