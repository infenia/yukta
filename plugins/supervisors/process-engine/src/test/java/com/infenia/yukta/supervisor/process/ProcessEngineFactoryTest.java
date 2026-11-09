// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.supervisor.process;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.infenia.yukta.plugin.control.ExecutionControlCommand.PauseWorkflowCommand;
import com.infenia.yukta.plugin.control.engine.Directive;
import com.infenia.yukta.plugin.control.engine.EngineConfig;
import com.infenia.yukta.plugin.control.engine.EngineContext;
import com.infenia.yukta.plugin.control.engine.Signal;
import com.infenia.yukta.plugin.control.engine.SupervisorEngine;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

/** The program decides; the server only checks that it may run and what it says. */
@SuppressWarnings({
  "PMD.AvoidDuplicateLiterals",
  "PMD.CloseResource",
  "PMD.FieldDeclarationsShouldBeAtStartOfClass",
  "PMD.LambdaCanBeMethodReference",
  "PMD.ShortMethodName",
  "PMD.TooManyMethods",
  "PMD.UseTryWithResources"
})
class ProcessEngineFactoryTest {

  private static final EngineContext CONTEXT =
      new EngineContext() {
        @Override
        public String self() {
          return "p1";
        }

        @Override
        public Flux<Instant> ticks(final Duration period) {
          return Flux.never();
        }

        @Override
        public Map<String, Object> state() {
          return Map.of();
        }
      };

  private static EngineConfig sh(final String script) {
    return new EngineConfig(Map.of("command", "sh", "args", List.of("-c", script)));
  }

  private final ProcessEngineFactory factory = new ProcessEngineFactory(Set.of("sh"), false);

  @Test
  void itDescribesItselfAndChecksTheSettings() {
    assertThat(factory.type()).isEqualTo("process");
    assertThat(factory.describe().canInitiate()).isTrue();
    assertThat(factory.getConfigSchema())
        .extracting(f -> f.name())
        .containsExactly("command", "args");
    factory.validate(sh("true"));
    assertThatThrownBy(() -> factory.validate(new EngineConfig(Map.of("command", "rm"))))
        .hasMessageContaining("allowed-commands");
    assertThatThrownBy(() -> factory.validate(new EngineConfig(Map.of())))
        .hasMessageContaining("command is required");
    assertThatThrownBy(() -> factory.validate(new EngineConfig(Map.of("command", " "))))
        .hasMessageContaining("command is required");
  }

  @Test
  void withNoListNothingRunsUnlessAnyIsAllowed() {
    final EngineConfig config = new EngineConfig(Map.of("command", "anything"));
    assertThatThrownBy(() -> new ProcessEngineFactory(Set.of(), false).validate(config))
        .isInstanceOf(IllegalArgumentException.class);
    new ProcessEngineFactory(Set.of(), true).validate(config);
  }

  @Test
  void programReadsSignalsAndAnswersWithDirectives() {
    // answers every line it reads with a pause, and a junk line the server must drop
    final SupervisorEngine engine =
        factory.create(
            sh(
                "while read line; do echo not-json; echo '{\"type\":\"command\",\"key\":\"k\","
                    + "\"cause\":\"tick\",\"reason\":\"r\",\"command\":{\"type\":\"PauseWorkflow\","
                    + "\"executionId\":\"run1\"}}'; done"),
            CONTEXT);
    try {
      StepVerifier.create(
              engine
                  .run(Flux.<Signal>just(new Signal.Tick(Instant.EPOCH)).concatWith(Flux.never()))
                  .take(1))
          .assertNext(
              d ->
                  assertThat(((Directive.Command) d).command())
                      .isEqualTo(new PauseWorkflowCommand("run1")))
          .verifyComplete();
    } finally {
      engine.close();
    }
  }

  @Test
  void programThatExitsFailsTheEngineSoItIsStartedAgain() {
    final SupervisorEngine engine = factory.create(sh("exit 0"), CONTEXT);
    StepVerifier.create(engine.run(Flux.never()))
        .expectErrorSatisfies(e -> assertThat(e).hasMessageContaining("ended"))
        .verify(Duration.ofSeconds(10));
  }

  @Test
  void programThatCannotStartFailsTheEngine() {
    final ProcessEngineFactory open = new ProcessEngineFactory(Set.of("/no/such/program"), false);
    final SupervisorEngine engine =
        open.create(new EngineConfig(Map.of("command", "/no/such/program")), CONTEXT);
    StepVerifier.create(engine.run(Flux.never()))
        .expectError(java.io.IOException.class)
        .verify(Duration.ofSeconds(10));
    engine.close();
  }

  @Test
  void theProgramRunsWithAnEmptyEnvironment() {
    final SupervisorEngine engine =
        factory.create(
            sh(
                "read line; test -z \"$HOME\" && echo"
                    + " '{\"type\":\"noOpinion\",\"answers\":\"clean\"}'; sleep 5"),
            CONTEXT);
    try {
      StepVerifier.create(
              engine
                  .run(Flux.<Signal>just(new Signal.Tick(Instant.EPOCH)).concatWith(Flux.never()))
                  .take(1))
          .expectNext(new Directive.NoOpinion("clean"))
          .verifyComplete();
    } finally {
      engine.close();
    }
  }

  @Test
  void argumentsMayBeOneTextWithLines() {
    final SupervisorEngine engine =
        factory.create(
            new EngineConfig(
                Map.of(
                    "command",
                    "sh",
                    "args",
                    "-c\necho '{\"type\":\"noOpinion\",\"answers\":\"x\"}'; sleep 5")),
            CONTEXT);
    try {
      StepVerifier.create(engine.run(Flux.never()).take(1))
          .expectNext(new Directive.NoOpinion("x"))
          .verifyComplete();
    } finally {
      engine.close();
    }
  }

  @Test
  void lineWithoutEndNeverFillsTheMemoryAndTheNextLineStillCounts() {
    final SupervisorEngine engine =
        factory.create(
            sh(
                "head -c 300000 /dev/zero | tr '\\0' x; echo; echo '{\"type\":\"command\","
                    + "\"key\":\"k\",\"cause\":\"tick\",\"reason\":\"r\","
                    + "\"command\":{\"type\":\"PauseWorkflow\",\"executionId\":\"run1\"}}';"
                    + " sleep 5"),
            CONTEXT);
    try {
      StepVerifier.create(engine.run(Flux.never()).take(1))
          .assertNext(
              d ->
                  assertThat(((Directive.Command) d).command())
                      .isEqualTo(new PauseWorkflowCommand("run1")))
          .verifyComplete();
    } finally {
      engine.close();
    }
  }

  private static java.io.BufferedReader reader(final String text) {
    return new java.io.BufferedReader(new java.io.StringReader(text));
  }

  @Test
  void boundedLineReadsLinesTheLastOneWithoutEndAndTheEndOfTheOutput() throws Exception {
    final var in = reader("abc\ntail");
    assertThat(ProcessEngineFactory.boundedLine(in)).isEqualTo("abc");
    assertThat(ProcessEngineFactory.boundedLine(in)).isEqualTo("tail");
    assertThat(ProcessEngineFactory.boundedLine(in)).isNull();
    assertThat(ProcessEngineFactory.boundedLine(reader(""))).isNull();
  }

  @Test
  void boundedLineThrowsAwayTheRestOfLongLineWithOrWithoutEnd() throws Exception {
    final String longText = "x".repeat(70_000);
    final var withEnd = reader(longText + "\nnext\n");
    assertThat(ProcessEngineFactory.boundedLine(withEnd)).isEmpty();
    assertThat(ProcessEngineFactory.boundedLine(withEnd)).isEqualTo("next");
    assertThat(ProcessEngineFactory.boundedLine(reader(longText))).isEmpty();
  }

  @Test
  void lineThatIsJsonButNotAnObjectIsDroppedAndEmptyArgumentsMeanNone() {
    factory.create(new EngineConfig(Map.of("command", "sh", "args", "")), CONTEXT).close();
    final SupervisorEngine engine =
        factory.create(
            sh(
                "echo '[1,2]'; echo 3; echo '{\"type\":\"noOpinion\",\"answers\":\"x\"}';"
                    + " sleep 5"),
            CONTEXT);
    try {
      StepVerifier.create(engine.run(Flux.never()).take(1))
          .assertNext(d -> assertThat(d).isEqualTo(new Directive.NoOpinion("x")))
          .verifyComplete();
    } finally {
      engine.close();
    }
  }

  @Test
  void signalsSentToProgramThatClosedItsInputAreDroppedAndTheEngineStillEnds() {
    final SupervisorEngine engine = factory.create(sh("exec 0<&-; sleep 1"), CONTEXT);
    StepVerifier.create(
            engine.run(
                Flux.<Signal>just(new Signal.Tick(Instant.EPOCH))
                    .delaySubscription(Duration.ofMillis(400))
                    .concatWith(Flux.never())))
        .expectErrorMessage("The program ended")
        .verify(Duration.ofSeconds(10));
  }
}
