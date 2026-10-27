// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.controlplane.composite;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.controlplane.model.ModelPlaneSettings;
import com.infenia.yukta.plugin.control.ControlDecision;
import com.infenia.yukta.plugin.control.ControlDecision.Source;
import com.infenia.yukta.plugin.control.ControlPlanePlugin;
import com.infenia.yukta.plugin.control.ControlRequest;
import com.infenia.yukta.plugin.control.Decision;
import com.infenia.yukta.plugin.control.ExecutionControlCommand;
import com.infenia.yukta.plugin.control.ExecutionEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import reactor.core.publisher.Mono;

@NoArgsConstructor
@SuppressWarnings({"PMD.CommentRequired", "PMD.TooManyMethods"})
class RulesFirstControlPlaneTest {

  private static final ExecutionControlCommand PAUSE =
      new ExecutionControlCommand.PauseWorkflowCommand("e1");

  private static final ExecutionControlCommand STOP =
      new ExecutionControlCommand.StopWorkflowCommand("e1", "model");

  private static final ExecutionEvent EVENT =
      new ExecutionEvent.NodeStatusChanged(
          "e1", "wf", "n1", "HTTP_REQUEST", "FAILURE", Map.of(), Instant.now());

  private static final ExecutionEvent OTHER =
      new ExecutionEvent.NodeStatusChanged(
          "e1", "wf", "n1", "HTTP_REQUEST", "SUCCESS", Map.of(), Instant.now());

  /** A plugin that always answers the same and counts how often it was asked. */
  private static final class Fixed implements ControlPlanePlugin {
    private final ControlDecision decision;
    private final Decision verdict;
    private final AtomicInteger asked = new AtomicInteger();

    /* default */ Fixed(final ControlDecision decision, final Decision verdict) {
      this.decision = decision;
      this.verdict = verdict;
    }

    @Override
    @SuppressWarnings("PMD.ShortMethodName")
    public String id() {
      return "fixed";
    }

    @Override
    public Mono<ControlDecision> decide(final ExecutionEvent event) {
      asked.incrementAndGet();
      return Mono.just(decision);
    }

    @Override
    public Mono<Decision> review(final ControlRequest request) {
      return Mono.just(verdict);
    }
  }

  /** A plugin whose decision is whatever Mono it is given, such as an error or nothing. */
  private static ControlPlanePlugin replying(final Mono<ControlDecision> reply) {
    return new ControlPlanePlugin() {
      @Override
      @SuppressWarnings("PMD.ShortMethodName")
      public String id() {
        return "broken";
      }

      @Override
      public Mono<ControlDecision> decide(final ExecutionEvent event) {
        return reply;
      }
    };
  }

  private static Fixed answering(final ExecutionControlCommand command, final Source source) {
    final List<ExecutionControlCommand> commands = command == null ? List.of() : List.of(command);
    return new Fixed(new ControlDecision(commands, 1, source), Decision.ALLOW);
  }

  @Test
  void decide_ruleHasCommands_modelNotAsked() {
    final Fixed rules = answering(PAUSE, Source.RULE);
    final Fixed model = answering(STOP, Source.MODEL);

    final ControlDecision decision = new RulesFirstControlPlane(rules, model).decide(EVENT).block();

    assertThat(decision.commands()).containsExactly(PAUSE);
    assertThat(decision.source()).isEqualTo(Source.RULE);
    assertThat(model.asked).hasValue(0);
  }

  @Test
  void decide_rulesSilent_modelDecides() {
    final Fixed rules = answering(null, Source.RULE);
    final Fixed model = answering(STOP, Source.MODEL);

    final ControlDecision decision = new RulesFirstControlPlane(rules, model).decide(EVENT).block();

    assertThat(decision.commands()).containsExactly(STOP);
    assertThat(decision.source()).isEqualTo(Source.MODEL);
  }

  private static void assertModelDecidesWhenRulesReply(final Mono<ControlDecision> rulesReply) {
    final Fixed model = answering(STOP, Source.MODEL);

    final ControlDecision decision =
        new RulesFirstControlPlane(replying(rulesReply), model).decide(EVENT).block();

    assertThat(decision.commands()).containsExactly(STOP);
    assertThat(model.asked).hasValue(1);
  }

  @Test
  void decide_rulesFail_countAsNoOpinionAndModelDecides() {
    assertModelDecidesWhenRulesReply(Mono.error(new IllegalStateException("bad rule")));
  }

  @Test
  void decide_rulesCompleteEmpty_countAsNoOpinionAndModelDecides() {
    assertModelDecidesWhenRulesReply(Mono.empty());
  }

  @Test
  void review_ruleDeny_stands() {
    final Fixed rules = new Fixed(ControlDecision.none(Source.RULE), Decision.deny("no"));
    final Fixed model = answering(STOP, Source.MODEL);

    final Decision verdict =
        new RulesFirstControlPlane(rules, model).review(new ControlRequest(STOP, null)).block();

    assertThat(verdict.allowed()).isFalse();
  }

  @Test
  void id_isRulesFirst() {
    final Fixed any = answering(null, Source.RULE);

    assertThat(new RulesFirstControlPlane(any, any).id()).isEqualTo("rules-first-control-plane");
  }

  @Test
  void binder_readsTheModelBelowTheRulesFirstPrefix() {
    final RulesFirstSettings settings =
        new Binder(
                new MapConfigurationPropertySource(
                    Map.of(
                        "yukta.control-plane.rules-first.rules-file", "/etc/yukta/rules.json",
                        "yukta.control-plane.rules-first.model.name", "tev1:0.8b",
                        "yukta.control-plane.rules-first.model.instructions", "Act?")))
            .bind("yukta.control-plane.rules-first", RulesFirstSettings.class)
            .get();

    assertThat(settings.rulesFile()).isEqualTo(Path.of("/etc/yukta/rules.json"));
    assertThat(settings.model().name()).isEqualTo("tev1:0.8b");
    assertThat(settings.model().instructions()).isEqualTo("Act?");
  }

  @Test
  void configuredConstructor_ruleHitSkipsUnreachableModel(@TempDir final Path dir)
      throws IOException {
    final Path file =
        Files.writeString(
            dir.resolve("rules.json"),
            "{\"rules\":[{\"id\":\"p\",\"when\":{\"status\":\"FAILURE\"},"
                + "\"then\":{\"command\":\"PauseWorkflow\"}}]}");
    final AtomicInteger asked = new AtomicInteger();
    final var plane =
        new RulesFirstControlPlane(
            (spec, state, questions) -> {
              asked.incrementAndGet();
              return Mono.error(new IllegalStateException("must not be asked"));
            },
            new RulesFirstSettings(
                file,
                new ModelPlaneSettings(
                    null,
                    null,
                    "nimble",
                    null,
                    null,
                    Duration.ofSeconds(1),
                    0.6,
                    null,
                    null,
                    null,
                    null,
                    null)));

    assertThat(plane.decide(EVENT).block().commands()).containsExactly(PAUSE);
    assertThat(asked).hasValue(0);
    assertThat(plane.decide(OTHER).block().commands()).isEmpty();
    assertThat(asked).hasValue(1);
  }
}
