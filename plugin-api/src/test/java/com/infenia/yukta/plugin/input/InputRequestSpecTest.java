// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.input;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

@SuppressWarnings({
  "PMD.AvoidDuplicateLiterals",
  "PMD.AvoidInstantiatingObjectsInLoops",
  "PMD.TooManyMethods",
  "PMD.UseConcurrentHashMap"
})
class InputRequestSpecTest {

  private static final List<InputOption> TWO =
      List.of(new InputOption("approve"), new InputOption("reject"));

  private static InputRequestSpec make(
      final InputKind kind,
      final List<InputOption> options,
      final String schema,
      final TimeoutAction onTimeout,
      final String defaultDecision) {
    return new InputRequestSpec(
        "key",
        kind,
        "Deploy?",
        Map.of(),
        options,
        schema,
        InputRisk.MEDIUM,
        Audience.CURRENT_TIER,
        Duration.ofMinutes(1),
        onTimeout,
        defaultDecision,
        null,
        "LLM_CALL");
  }

  private static InputRequestSpec withPrompt(final String prompt) {
    return new InputRequestSpec(
        "key",
        InputKind.TEXT,
        prompt,
        Map.of(),
        List.of(),
        null,
        InputRisk.LOW,
        Audience.CURRENT_TIER,
        Duration.ofMinutes(1),
        TimeoutAction.FAIL,
        null,
        null,
        "X");
  }

  @Test
  void anApprovalMayHaveNoOptions() {
    final InputRequestSpec spec =
        make(InputKind.APPROVAL, List.of(), null, TimeoutAction.FAIL, null);
    assertThat(spec.options()).isEmpty();
    assertThat(spec.requester()).isEqualTo("LLM_CALL");
  }

  @Test
  void choiceNeedsTwoOptions() {
    assertThatThrownBy(
            () ->
                make(
                    InputKind.CHOICE,
                    List.of(new InputOption("a")),
                    null,
                    TimeoutAction.FAIL,
                    null))
        .hasMessage("A CHOICE request needs at least two options");
    assertThat(make(InputKind.CHOICE, TWO, null, TimeoutAction.FAIL, null).options()).hasSize(2);
  }

  @Test
  void onlyApprovalsAndChoicesHaveOptions() {
    for (final InputKind kind : List.of(InputKind.TEXT, InputKind.QUERY)) {
      assertThatThrownBy(() -> make(kind, TWO, null, TimeoutAction.FAIL, null))
          .hasMessage("A " + kind + " request has no options");
    }
  }

  @Test
  void optionIdsAreUniqueAndFew() {
    assertThatThrownBy(
            () ->
                make(
                    InputKind.CHOICE,
                    List.of(new InputOption("a"), new InputOption("a", "other")),
                    null,
                    TimeoutAction.FAIL,
                    null))
        .hasMessage("Option id used twice: a");
    final List<InputOption> many =
        java.util.stream.IntStream.range(0, 21).mapToObj(i -> new InputOption("o" + i)).toList();
    assertThatThrownBy(() -> make(InputKind.CHOICE, many, null, TimeoutAction.FAIL, null))
        .hasMessage("A request offers at most 20 options");
  }

  @Test
  void structuredRequestNeedsJsonObjectSchema() {
    assertThat(
            make(InputKind.STRUCTURED, List.of(), "{\"type\":\"object\"}", TimeoutAction.FAIL, null)
                .responseSchema())
        .isEqualTo("{\"type\":\"object\"}");
    assertThatThrownBy(() -> make(InputKind.STRUCTURED, List.of(), null, TimeoutAction.FAIL, null))
        .hasMessage("A STRUCTURED request needs a responseSchema");
    assertThatThrownBy(() -> make(InputKind.STRUCTURED, List.of(), " ", TimeoutAction.FAIL, null))
        .hasMessage("A STRUCTURED request needs a responseSchema");
    assertThatThrownBy(
            () -> make(InputKind.STRUCTURED, List.of(), "{not json", TimeoutAction.FAIL, null))
        .hasMessage("responseSchema is not valid JSON");
    assertThatThrownBy(() -> make(InputKind.STRUCTURED, List.of(), "[1]", TimeoutAction.FAIL, null))
        .hasMessage("responseSchema must be a JSON object");
    assertThatThrownBy(
            () ->
                make(
                    InputKind.STRUCTURED,
                    List.of(),
                    "{\"d\":\"" + "x".repeat(17_000) + "\"}",
                    TimeoutAction.FAIL,
                    null))
        .hasMessageContaining("responseSchema is longer than");
  }

  @Test
  void onlyStructuredRequestHasSchema() {
    assertThatThrownBy(() -> make(InputKind.APPROVAL, List.of(), "{}", TimeoutAction.FAIL, null))
        .hasMessage("Only a STRUCTURED request has a responseSchema");
  }

  @Test
  void useDefaultNeedsDecisionThatIsOption() {
    assertThatThrownBy(() -> make(InputKind.APPROVAL, TWO, null, TimeoutAction.USE_DEFAULT, null))
        .hasMessage("USE_DEFAULT needs a defaultDecision");
    assertThatThrownBy(() -> make(InputKind.APPROVAL, TWO, null, TimeoutAction.USE_DEFAULT, " "))
        .hasMessage("USE_DEFAULT needs a defaultDecision");
    assertThatThrownBy(
            () -> make(InputKind.APPROVAL, TWO, null, TimeoutAction.USE_DEFAULT, "maybe"))
        .hasMessage("defaultDecision is not one of the options");
    assertThat(
            make(InputKind.APPROVAL, TWO, null, TimeoutAction.USE_DEFAULT, "reject")
                .defaultDecision())
        .isEqualTo("reject");
    // without options any decision will do, and a default is not needed for the other actions
    assertThat(
            make(InputKind.APPROVAL, List.of(), null, TimeoutAction.USE_DEFAULT, "reject")
                .defaultDecision())
        .isEqualTo("reject");
    assertThat(make(InputKind.APPROVAL, TWO, null, TimeoutAction.ESCALATE, null).onTimeout())
        .isEqualTo(TimeoutAction.ESCALATE);
  }

  @Test
  void theTtlIsPositive() {
    for (final Duration bad : List.of(Duration.ZERO, Duration.ofSeconds(-1))) {
      assertThatThrownBy(
              () ->
                  new InputRequestSpec(
                      "k",
                      InputKind.TEXT,
                      "p",
                      Map.of(),
                      List.of(),
                      null,
                      InputRisk.LOW,
                      Audience.CURRENT_TIER,
                      bad,
                      TimeoutAction.FAIL,
                      null,
                      null,
                      "X"))
          .hasMessage("ttl must be positive");
    }
  }

  @Test
  void thePromptIsPresentAndCapped() {
    assertThatThrownBy(() -> withPrompt(" ")).hasMessage("prompt must not be blank");
    assertThatThrownBy(() -> withPrompt("x".repeat(4097)))
        .hasMessage("prompt is longer than 4096 bytes");
    assertThatCode(() -> withPrompt("x".repeat(4096))).doesNotThrowAnyException();
  }

  @Test
  void theContextIsCappedAndCopied() {
    final Map<String, Object> context = new HashMap<>();
    context.put("a", null);
    final InputRequestSpec spec =
        new InputRequestSpec(
            "k",
            InputKind.TEXT,
            "p",
            context,
            List.of(),
            null,
            InputRisk.LOW,
            Audience.CURRENT_TIER,
            Duration.ofMinutes(1),
            TimeoutAction.FAIL,
            null,
            null,
            "X");
    context.put("b", "later");
    assertThat(spec.context()).containsOnlyKeys("a");
    assertThatThrownBy(() -> spec.context().put("c", "d"))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(
            () ->
                new InputRequestSpec(
                    "k",
                    InputKind.TEXT,
                    "p",
                    Map.of("big", "x".repeat(17_000)),
                    List.of(),
                    null,
                    InputRisk.LOW,
                    Audience.CURRENT_TIER,
                    Duration.ofMinutes(1),
                    TimeoutAction.FAIL,
                    null,
                    null,
                    "X"))
        .hasMessage("context is longer than 16384 bytes");
  }

  @Test
  void missingContextOrOptionListMeansEmpty() {
    final InputRequestSpec spec =
        new InputRequestSpec(
            "k",
            InputKind.APPROVAL,
            "p",
            null,
            null,
            null,
            InputRisk.LOW,
            Audience.CURRENT_TIER,
            Duration.ofMinutes(1),
            TimeoutAction.FAIL,
            null,
            null,
            "X");
    assertThat(spec.context()).isEmpty();
    assertThat(spec.options()).isEmpty();
  }

  @Test
  void theRouteNameAndRequesterAreChecked() {
    final java.util.function.BiFunction<String, String, InputRequestSpec> build =
        (route, requester) ->
            new InputRequestSpec(
                "k",
                InputKind.TEXT,
                "p",
                Map.of(),
                List.of(),
                null,
                InputRisk.LOW,
                Audience.CURRENT_TIER,
                Duration.ofMinutes(1),
                TimeoutAction.FAIL,
                null,
                route,
                requester);
    assertThat(build.apply("ops.fast", "X").routeName()).isEqualTo("ops.fast");
    assertThatThrownBy(() -> build.apply("bad name", "X"))
        .hasMessage("routeName must match [A-Za-z0-9._-]{1,64}");
    assertThatThrownBy(() -> build.apply(null, " ")).hasMessage("requester must not be blank");
    assertThatThrownBy(() -> build.apply(null, "x".repeat(65)))
        .hasMessage("requester is longer than 64 characters");
  }

  @Test
  void theRequiredFieldsAreChecked() {
    assertThatThrownBy(
            () ->
                new InputRequestSpec(
                    " ",
                    InputKind.TEXT,
                    "p",
                    Map.of(),
                    List.of(),
                    null,
                    InputRisk.LOW,
                    Audience.CURRENT_TIER,
                    Duration.ofMinutes(1),
                    TimeoutAction.FAIL,
                    null,
                    null,
                    "X"))
        .hasMessage("key must not be blank");
    assertThatThrownBy(
            () ->
                new InputRequestSpec(
                    "k",
                    null,
                    "p",
                    Map.of(),
                    List.of(),
                    null,
                    InputRisk.LOW,
                    Audience.CURRENT_TIER,
                    Duration.ofMinutes(1),
                    TimeoutAction.FAIL,
                    null,
                    null,
                    "X"))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(
            () ->
                new InputRequestSpec(
                    "k",
                    InputKind.TEXT,
                    "p",
                    Map.of(),
                    List.of(),
                    null,
                    null,
                    Audience.CURRENT_TIER,
                    Duration.ofMinutes(1),
                    TimeoutAction.FAIL,
                    null,
                    null,
                    "X"))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(
            () ->
                new InputRequestSpec(
                    "k",
                    InputKind.TEXT,
                    "p",
                    Map.of(),
                    List.of(),
                    null,
                    InputRisk.LOW,
                    null,
                    Duration.ofMinutes(1),
                    TimeoutAction.FAIL,
                    null,
                    null,
                    "X"))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(
            () ->
                new InputRequestSpec(
                    "k",
                    InputKind.TEXT,
                    "p",
                    Map.of(),
                    List.of(),
                    null,
                    InputRisk.LOW,
                    Audience.CURRENT_TIER,
                    Duration.ofMinutes(1),
                    null,
                    null,
                    null,
                    "X"))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(
            () ->
                new InputRequestSpec(
                    "k",
                    InputKind.TEXT,
                    "p",
                    Map.of(),
                    List.of(),
                    null,
                    InputRisk.LOW,
                    Audience.CURRENT_TIER,
                    null,
                    TimeoutAction.FAIL,
                    null,
                    null,
                    "X"))
        .isInstanceOf(NullPointerException.class);
  }
}
