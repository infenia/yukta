// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.core.flow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.message.Message;
import com.infenia.yukta.plugin.core.flow.InputFakes.Gateway;
import com.infenia.yukta.plugin.core.flow.InputFakes.ListStore;
import com.infenia.yukta.plugin.input.InputExpiredException;
import com.infenia.yukta.plugin.input.InputKind;
import com.infenia.yukta.plugin.input.InputOption;
import com.infenia.yukta.plugin.input.InputResponse;
import com.infenia.yukta.plugin.input.InputRisk;
import com.infenia.yukta.plugin.input.Responder;
import com.infenia.yukta.plugin.input.TimeoutAction;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/** Tests for the ASK_INPUT processor. */
@NoArgsConstructor
@SuppressWarnings({"PMD.AvoidDuplicateLiterals", "PMD.TooManyMethods"})
class AskInputProcessorTest {

  private final Message<?> input = DefaultMessage.create(UUID.randomUUID(), "work");

  private static Map<String, Object> config(final Object... pairs) {
    final Map<String, Object> config = new HashMap<>();
    config.put("prompt", "Deploy?");
    for (int i = 0; i < pairs.length; i += 2) {
      config.put((String) pairs[i], pairs[i + 1]);
    }
    return config;
  }

  private Flux<Message<?>> run(
      final AskInputProcessor processor, final Map<String, Object> config) {
    return processor.process(Flux.just(input), config).contextWrite(InputFakes.NODE);
  }

  private static AskInputProcessor processor(final Gateway gateway) {
    return new AskInputProcessor(InputFakes.provider(gateway), InputFakes.noResolver());
  }

  @Test
  void answerTagsTheMessage() {
    final Gateway gateway =
        new Gateway(
            _ ->
                Mono.just(
                    new InputResponse(
                        "req-9",
                        "approve",
                        "go ahead",
                        Map.of("n", 1),
                        new Responder(Responder.Type.SUPERVISOR, "guard"),
                        null,
                        0.8,
                        Instant.EPOCH)));
    StepVerifier.create(run(processor(gateway), config()))
        .assertNext(
            out -> {
              assertThat(out.payload()).isEqualTo("work");
              assertThat(out.metadata())
                  .containsEntry("input.requestId", "req-9")
                  .containsEntry("input.decision", "approve")
                  .containsEntry("input.text", "go ahead")
                  .containsEntry("input.data", Map.of("n", 1))
                  .containsEntry("input.responder", "SUPERVISOR:guard")
                  .containsEntry("input.confidence", 0.8);
            })
        .verifyComplete();
  }

  @Test
  void defaultsMakeMediumRiskApprovalWithApproveAndReject() {
    final Gateway gateway = InputFakes.answering("approve", null);
    run(processor(gateway), config()).blockLast();
    final var spec = gateway.asked.getFirst();
    assertThat(spec.kind()).isEqualTo(InputKind.APPROVAL);
    assertThat(spec.risk()).isEqualTo(InputRisk.MEDIUM);
    assertThat(spec.options()).extracting(InputOption::id).containsExactly("approve", "reject");
    assertThat(spec.ttl()).isEqualTo(Duration.ofSeconds(3600));
    assertThat(spec.onTimeout()).isEqualTo(TimeoutAction.FAIL);
    assertThat(spec.prompt()).isEqualTo("Deploy?");
    assertThat(spec.requester()).isEqualTo("ASK_INPUT");
  }

  @Test
  void everyKindIsAccepted() {
    final Gateway gateway = InputFakes.answering("x", "free");
    run(
            processor(gateway),
            config("kind", "CHOICE", "options", List.of("a", Map.of("id", "b", "label", "B"))))
        .blockLast();
    run(processor(gateway), config("kind", "TEXT")).blockLast();
    run(
            processor(gateway),
            config("kind", "STRUCTURED", "responseSchema", Map.of("type", "object")))
        .blockLast();
    assertThat(gateway.asked)
        .extracting("kind")
        .containsExactly(InputKind.CHOICE, InputKind.TEXT, InputKind.STRUCTURED);
    assertThat(gateway.asked.get(0).options())
        .extracting(InputOption::label)
        .containsExactly("a", "B");
    assertThat(gateway.asked.get(2).responseSchema()).contains("object");
  }

  @Test
  void audienceNameRouteAndRiskComeFromConfig() {
    final Gateway gateway = InputFakes.answering("approve", null);
    run(
            processor(gateway),
            config(
                "audience",
                List.of("role:ADMIN"),
                "name",
                "gate",
                "route",
                "ops",
                "risk",
                "HIGH",
                "ttlSeconds",
                60))
        .blockLast();
    final var spec = gateway.asked.getFirst();
    assertThat(spec.audience().selectors()).containsExactly("role:ADMIN");
    assertThat(spec.routeName()).isEqualTo("ops");
    assertThat(spec.risk()).isEqualTo(InputRisk.HIGH);
    assertThat(spec.ttl()).isEqualTo(Duration.ofSeconds(60));
    run(processor(gateway), config("name", "other")).blockLast();
    assertThat(gateway.asked.get(1).key()).isNotEqualTo(spec.key());
  }

  @Test
  void useDefaultIsPassedToTheBroker() {
    final Gateway gateway = InputFakes.answering("reject", null);
    run(processor(gateway), config("onTimeout", "USE_DEFAULT", "defaultDecision", "reject"))
        .blockLast();
    assertThat(gateway.asked.getFirst().onTimeout()).isEqualTo(TimeoutAction.USE_DEFAULT);
    assertThat(gateway.asked.getFirst().defaultDecision()).isEqualTo("reject");
  }

  @Test
  void timeoutFailsByDefaultAndContinuesWhenAsked() {
    final Gateway gateway = new Gateway(_ -> Mono.error(new InputExpiredException("r7", "late")));
    StepVerifier.create(run(processor(gateway), config())).verifyError(IllegalStateException.class);
    StepVerifier.create(run(processor(gateway), config("onTimeout", "CONTINUE")))
        .assertNext(
            out ->
                assertThat(out.metadata())
                    .containsEntry("input.decision", "timeout")
                    .containsEntry("input.requestId", "r7"))
        .verifyComplete();
  }

  @Test
  void promptIsCleanedOfControlCharacters() {
    final Gateway gateway = InputFakes.answering("approve", null);
    run(processor(gateway), config("prompt", "Hi\u0000 there")).blockLast();
    assertThat(gateway.asked.getFirst().prompt()).isEqualTo("Hi there");
  }

  @Test
  void blankPromptAfterCleaningFails() {
    final Gateway gateway = InputFakes.answering("approve", null);
    StepVerifier.create(run(processor(gateway), config("prompt", "\u0000\u0001 ")))
        .verifyError(IllegalArgumentException.class);
  }

  @Test
  void answerIsRecordedAndReplayedWithoutAskingAgain() {
    final ListStore store = new ListStore();
    final Gateway first = InputFakes.answering("approve", "ok");
    run(new AskInputProcessor(store, InputFakes.provider(first), InputFakes.noResolver()), config())
        .blockLast();
    assertThat(store.kept).hasSize(1);

    final Gateway second = InputFakes.answering("reject", "late");
    StepVerifier.create(
            run(
                new AskInputProcessor(store, InputFakes.provider(second), InputFakes.noResolver()),
                config()))
        .assertNext(out -> assertThat(out.metadata()).containsEntry("input.decision", "approve"))
        .verifyComplete();
    assertThat(second.asked).isEmpty();
  }

  @Test
  void failingStoreNeverFailsTheAsk() {
    final ListStore store = new ListStore();
    store.failing = true;
    StepVerifier.create(
            run(
                new AskInputProcessor(
                    store,
                    InputFakes.provider(InputFakes.answering("approve", null)),
                    InputFakes.noResolver()),
                config()))
        .expectNextCount(1)
        .verifyComplete();
  }

  @Test
  void withoutGatewayTheNodeFailsClearly() {
    StepVerifier.create(run(processor(null), config()))
        .verifyErrorSatisfies(e -> assertThat(e).hasMessageContaining("No InputGateway"));
  }

  @Test
  void validationChecksEveryKey() {
    final AskInputProcessor processor = processor(null);
    assertThat(processor.validateConfig(config()).block()).isNull();
    final List<Map<String, Object>> bad =
        List.of(
            Map.of(),
            config("prompt", " "),
            config("kind", "QUERY"),
            config("kind", "poem"),
            config("kind", "CHOICE"),
            config("kind", "TEXT", "options", List.of("a")),
            config("kind", "STRUCTURED"),
            config("risk", "SEVERE"),
            config("ttlSeconds", 0),
            config("onTimeout", "WAIT"),
            config("onTimeout", "USE_DEFAULT"),
            config("onTimeout", "USE_DEFAULT", "defaultDecision", "maybe"),
            config("audience", "everyone"),
            config("audience", List.of("anyone")),
            config("name", " "),
            config("route", "bad route!"),
            config("responseSchema", "{}"),
            config("options", "approve"));
    for (final Map<String, Object> config : bad) {
      assertThatThrownBy(() -> processor.validateConfig(config).block())
          .as(config.toString())
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void metadataOfThePluginIsDescribed() {
    final AskInputProcessor processor = processor(null);
    assertThat(processor.getType()).isEqualTo("ASK_INPUT");
    assertThat(processor.requiresControlPlane()).isTrue();
    assertThat(processor.getDefaultTimeout(config("ttlSeconds", 100)))
        .isEqualTo(Duration.ofSeconds(130));
    assertThat(processor.getDefaultTimeout(config())).isEqualTo(Duration.ofSeconds(3630));
    assertThat(processor.getConfigSchema())
        .extracting("name")
        .containsExactly(
            "prompt",
            "kind",
            "options",
            "risk",
            "ttlSeconds",
            "onTimeout",
            "defaultDecision",
            "responseSchema",
            "audience",
            "name",
            "route");
    assertThat(processor.describe().usagePattern()).contains("input.decision");
  }
}
