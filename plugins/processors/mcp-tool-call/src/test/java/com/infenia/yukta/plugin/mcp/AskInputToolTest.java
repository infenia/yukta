// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.infenia.yukta.plugin.input.InputExpiredException;
import com.infenia.yukta.plugin.input.InputGateway;
import com.infenia.yukta.plugin.input.InputIdentity;
import com.infenia.yukta.plugin.input.InputKind;
import com.infenia.yukta.plugin.input.InputLimitException;
import com.infenia.yukta.plugin.input.InputRequestSpec;
import com.infenia.yukta.plugin.input.InputResponse;
import com.infenia.yukta.plugin.input.InputRisk;
import com.infenia.yukta.plugin.input.Responder;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

/** Tests for the ask_for_input tool of MCP_TOOL_CALL. */
@NoArgsConstructor
@SuppressWarnings({"PMD.AvoidDuplicateLiterals", "PMD.TooManyMethods", "PMD.UseConcurrentHashMap"})
class AskInputToolTest {

  private final List<InputRequestSpec> asked = new ArrayList<>();
  private Function<InputRequestSpec, Mono<InputResponse>> reply =
      _ ->
          Mono.just(
              new InputResponse(
                  "r1",
                  "approve",
                  "fine",
                  Map.of("k", "v"),
                  new Responder(Responder.Type.HUMAN, "op"),
                  null,
                  null,
                  Instant.EPOCH));

  private final InputGateway gateway =
      spec -> {
        asked.add(spec);
        return reply.apply(spec);
      };

  /** A node run of its own, so the per-run question count never leaks between tests. */
  private static Context node() {
    return Context.of(
        "sessionId",
        "s",
        "workflowId",
        "w",
        "executionId",
        UUID.randomUUID().toString(),
        "nodeId",
        "n");
  }

  private static Map<String, Object> config(final Object... pairs) {
    final Map<String, Object> config = new HashMap<>();
    config.put("allowAskInput", true);
    for (int i = 0; i + 1 < pairs.length; i += 2) {
      config.put((String) pairs[i], pairs[i + 1]);
    }
    return config;
  }

  private McpCallResult ask(
      final Map<String, Object> config, final Context ctx, final Map<String, Object> args) {
    return AskInputTool.ask(() -> gateway, config, ctx, "m1", args).block();
  }

  @Test
  void servesOnlyWhenAllowedAndNamed() {
    assertThat(AskInputTool.serves(config(), "ask_for_input")).isTrue();
    assertThat(AskInputTool.serves(config(), "other")).isFalse();
    assertThat(AskInputTool.serves(Map.of(), "ask_for_input")).isFalse();
    assertThat(AskInputTool.serves(Map.of("allowAskInput", false), "ask_for_input")).isFalse();
  }

  @Test
  void answerGoesBackAsTextAndData() {
    final var result = ask(config(), node(), Map.of("prompt", "Proceed?"));
    assertThat(result.isError()).isFalse();
    assertThat(result.text()).isEqualTo("approve: fine");
    assertThat(result.structured())
        .isEqualTo(
            Map.of(
                "requestId",
                "r1",
                "decision",
                "approve",
                "text",
                "fine",
                "data",
                Map.of("k", "v"),
                "responder",
                "HUMAN:op"));
  }

  @Test
  void modelCannotLowerTheRiskBelowTheFloor() {
    ask(config(), node(), Map.of("prompt", "p", "risk", "LOW"));
    ask(config("inputRiskFloor", "HIGH"), node(), Map.of("prompt", "p", "risk", "MEDIUM"));
    ask(config(), node(), Map.of("prompt", "p", "risk", "HIGH"));
    ask(config(), node(), Map.of("prompt", "p"));
    assertThat(asked)
        .extracting(InputRequestSpec::risk)
        .containsExactly(InputRisk.MEDIUM, InputRisk.HIGH, InputRisk.HIGH, InputRisk.MEDIUM);
  }

  @Test
  void modelCannotSetTtlAudienceOrRoute() {
    final Map<String, Object> args = new HashMap<>();
    args.put("prompt", "p");
    args.put("ttlSeconds", 5);
    args.put("ttl", 5);
    args.put("audience", List.of("supervisor:*"));
    args.put("route", "evil");
    args.put("onTimeout", "USE_DEFAULT");
    args.put("responseSchema", "{}");
    ask(
        config("inputTtlSeconds", 120, "inputAudience", List.of("role:ADMIN"), "inputRoute", "ops"),
        node(),
        args);
    final InputRequestSpec spec = asked.getFirst();
    assertThat(spec.ttl()).isEqualTo(Duration.ofSeconds(120));
    assertThat(spec.audience().selectors()).containsExactly("role:ADMIN");
    assertThat(spec.routeName()).isEqualTo("ops");
    assertThat(spec.onTimeout().name()).isEqualTo("FAIL");
    assertThat(spec.responseSchema()).isNull();
  }

  @Test
  void kindsAreLimitedAndChoiceNeedsOptions() {
    assertThat(ask(config(), node(), Map.of("prompt", "p", "kind", "STRUCTURED")).isError())
        .isTrue();
    assertThat(ask(config(), node(), Map.of("prompt", "p", "kind", "CHOICE")).isError()).isTrue();
    assertThat(ask(config(), node(), Map.of("prompt", "p", "kind", "nope")).isError()).isTrue();
    assertThat(asked).isEmpty();
    ask(
        config(),
        node(),
        Map.of("prompt", "p", "kind", "CHOICE", "options", List.of("a", "b", "a")));
    ask(config(), node(), Map.of("prompt", "p", "kind", "TEXT", "options", List.of("a")));
    assertThat(asked.get(0).kind()).isEqualTo(InputKind.CHOICE);
    assertThat(asked.get(0).options()).hasSize(2);
    assertThat(asked.get(1).options()).isEmpty();
  }

  @Test
  void promptIsCleanedAndCapped() {
    final String bidi = new String(Character.toChars(0x202E));
    ask(config(), node(), Map.of("prompt", "a\u0000b" + bidi + "c" + "x".repeat(10_000)));
    assertThat(asked.getFirst().prompt()).startsWith("abc").hasSizeLessThanOrEqualTo(4096);
    assertThat(asked.getFirst().prompt()).doesNotContain("\u0000", bidi);
    assertThat(ask(config(), node(), Map.of("prompt", "\u0000 ")).isError()).isTrue();
    assertThat(ask(config(), node(), Map.of()).isError()).isTrue();
  }

  @Test
  void askLimitPerNodeRun() {
    final Context ctx = node();
    final Map<String, Object> config = config("inputMaxAsks", 2);
    assertThat(ask(config, ctx, Map.of("prompt", "one")).isError()).isFalse();
    assertThat(ask(config, ctx, Map.of("prompt", "two")).isError()).isFalse();
    final var third = ask(config, ctx, Map.of("prompt", "three"));
    assertThat(third.isError()).isTrue();
    assertThat(third.error()).contains("2 questions");
    assertThat(asked).hasSize(2);
    // another run starts afresh
    assertThat(ask(config, node(), Map.of("prompt", "one")).isError()).isFalse();
  }

  @Test
  void defaultLimitIsThree() {
    final Context ctx = node();
    for (int i = 0; i < 3; i++) {
      assertThat(ask(config(), ctx, Map.of("prompt", "q" + i)).isError()).isFalse();
    }
    assertThat(ask(config(), ctx, Map.of("prompt", "q4")).isError()).isTrue();
  }

  @Test
  void noAnswerIsToolErrorUnlessConfiguredToFail() {
    reply = _ -> Mono.error(new InputExpiredException("r1", "late"));
    final var result = ask(config("inputTtlSeconds", 30), node(), Map.of("prompt", "p"));
    assertThat(result.isError()).isTrue();
    assertThat(result.error()).contains("30 s");

    assertThatThrownBy(() -> ask(config("inputOnTimeout", "FAIL"), node(), Map.of("prompt", "p")))
        .isInstanceOf(InputExpiredException.class)
        .hasMessageContaining("late");
  }

  @Test
  void limitRefusalIsToolError() {
    reply = _ -> Mono.error(new InputLimitException("k", "too many open"));
    final var result = ask(config(), node(), Map.of("prompt", "p"));
    assertThat(result.isError()).isTrue();
    assertThat(result.error()).contains("too many open");
  }

  @Test
  void outsideNodeTheAskFails() {
    assertThatThrownBy(() -> ask(config(), Context.empty(), Map.of("prompt", "p")))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void validationRefusesBadOptions() {
    AskInputTool.validate(Map.of());
    AskInputTool.validate(
        config("inputRiskFloor", "low", "inputOnTimeout", "FAIL", "inputRoute", "r"));
    for (final Map<String, Object> bad :
        List.<Map<String, Object>>of(
            config("inputTtlSeconds", 0),
            config("inputRiskFloor", "SEVERE"),
            config("inputAudience", "all"),
            config("inputAudience", List.of("anyone")),
            config("inputMaxAsks", -1),
            config("inputOnTimeout", "LATER"),
            config("inputRoute", 5),
            Map.of("allowAskInput", "true"))) {
      assertThatThrownBy(() -> AskInputTool.validate(bad))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void waitIsZeroWhenOff() {
    assertThat(AskInputTool.wait(Map.of())).isEqualTo(Duration.ZERO);
    assertThat(AskInputTool.wait(config())).isEqualTo(Duration.ofSeconds(3600));
  }

  @Test
  void routeIsOnlyNamedRoute() {
    ask(config("inputRoute", " "), node(), Map.of("prompt", "p"));
    ask(config("inputRoute", "ops"), node(), Map.of("prompt", "p"));
    ask(config(), node(), Map.of("prompt", "p"));
    assertThat(asked).extracting(InputRequestSpec::routeName).containsExactly(null, "ops", null);
  }

  @Test
  void blankAndRepeatedOptionsAreDropped_andTooManyAreRefused() {
    ask(
        config(),
        node(),
        Map.of("prompt", "p", "kind", "CHOICE", "options", List.of("a", " ", "a", "b")));
    assertThat(asked.getFirst().options()).hasSize(2);
    final List<String> many = new ArrayList<>();
    for (int i = 0; i < 21; i++) {
      many.add("o" + i);
    }
    final var result =
        ask(config(), node(), Map.of("prompt", "p", "kind", "CHOICE", "options", many));
    assertThat(result.isError()).isTrue();
    assertThat(result.error()).contains("at most 20");
  }

  @Test
  void anAnswerWithoutTextIsJustTheDecision() {
    reply =
        _ ->
            Mono.just(
                new InputResponse(
                    "r1",
                    "reject",
                    null,
                    Map.of(),
                    new Responder(Responder.Type.HUMAN, "op"),
                    null,
                    null,
                    Instant.EPOCH));
    assertThat(ask(config(), node(), Map.of("prompt", "p")).text()).isEqualTo("reject");
    reply =
        _ ->
            Mono.just(
                new InputResponse(
                    "r1",
                    "reject",
                    " ",
                    Map.of(),
                    new Responder(Responder.Type.HUMAN, "op"),
                    null,
                    null,
                    Instant.EPOCH));
    assertThat(ask(config(), node(), Map.of("prompt", "p")).text()).isEqualTo("reject");
  }

  @Test
  void numbersMustBeNumbers_andErrorIsTheExplicitDefault() {
    assertThatThrownBy(() -> AskInputTool.validate(config("inputTtlSeconds", "soon")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> AskInputTool.validate(config("inputMaxAsks", "many")))
        .isInstanceOf(IllegalArgumentException.class);
    AskInputTool.validate(config("inputOnTimeout", "ERROR"));
  }

  @Test
  @SuppressWarnings("PMD.AvoidInstantiatingObjectsInLoops")
  void theOldestNodeRunsAreForgotten() {
    final InputIdentity first = new InputIdentity("s", "w", "first-run", "n");
    assertThat(AskInputTool.countAsk(first, 1)).isTrue();
    for (int i = 0; i < 10_000; i++) {
      AskInputTool.countAsk(new InputIdentity("s", "w", "run-" + i, "n"), 1);
    }
    // the count of the first run was dropped, so it may ask again
    assertThat(AskInputTool.countAsk(first, 1)).isTrue();
  }
}
