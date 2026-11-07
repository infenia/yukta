// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.supervisor.guards;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.infenia.yukta.plugin.control.ExecutionControlCommand;
import com.infenia.yukta.plugin.control.ExecutionEvent;
import com.infenia.yukta.plugin.control.ExecutionSupervisor;
import com.infenia.yukta.plugin.control.Supervisor;
import com.infenia.yukta.plugin.core.ConfigField;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

/** The failure guard counts failures and acts once. */
@NoArgsConstructor
@SuppressWarnings({"PMD.AvoidDuplicateLiterals", "PMD.TooManyMethods"})
class FailureGuardFactoryTest {

  private final FailureGuardFactory factory = new FailureGuardFactory();

  private ExecutionSupervisor guard(final Map<String, Object> settings) {
    return factory.create(new Supervisor("g", FailureGuardFactory.TYPE, settings, false));
  }

  private static ExecutionEvent failure(final String node, final Map<String, Object> metadata) {
    return new ExecutionEvent.NodeStatusChanged(
        "e1", "s", "w", node, "HTTP_REQUEST", "FAILURE", metadata, Instant.now());
  }

  private static ExecutionEvent running(final String node) {
    return new ExecutionEvent.NodeStatusChanged(
        "e1", "s", "w", node, "HTTP_REQUEST", "RUNNING", Map.of(), Instant.now());
  }

  private static List<ExecutionControlCommand> commands(
      final ExecutionSupervisor guard, final ExecutionEvent... events) {
    return guard.supervise(Flux.just(events)).collectList().block();
  }

  @Test
  void pausesTheWorkflowAfterTheThirdFailure() {
    final ExecutionSupervisor guard = guard(Map.of());

    assertThat(commands(guard, failure("a", Map.of()), failure("b", Map.of()))).isEmpty();
    assertThat(
            commands(
                guard(Map.of()),
                failure("a", Map.of()),
                failure("b", Map.of()),
                failure("c", Map.of())))
        .singleElement()
        .isInstanceOf(ExecutionControlCommand.PauseWorkflowCommand.class);
  }

  @Test
  void otherStatusesDoNotCount() {
    assertThat(
            commands(
                guard(Map.of("failures", 2)), failure("a", Map.of()), running("a"), running("b")))
        .isEmpty();
  }

  @Test
  void actionDecidesTheCommandAndTheDefaultAllowedCommands() {
    assertThat(
            commands(
                guard(Map.of("failures", 1, "action", "stop_workflow")), failure("a", Map.of())))
        .singleElement()
        .isInstanceOf(ExecutionControlCommand.StopWorkflowCommand.class);
    assertThat(
            commands(guard(Map.of("failures", 1, "action", "PAUSE_NODE")), failure("a", Map.of())))
        .singleElement()
        .isInstanceOf(ExecutionControlCommand.PauseNodeCommand.class);
    assertThat(
            factory.defaultAllowedCommands(
                new Supervisor("g", "failure-guard", Map.of("action", "STOP_WORKFLOW"), false)))
        .containsExactly("StopWorkflowCommand");
    assertThat(
            factory.defaultAllowedCommands(new Supervisor("g", "failure-guard", Map.of(), false)))
        .containsExactly("PauseWorkflowCommand");
    assertThat(factory.defaultAllowedCommands())
        .containsExactlyInAnyOrder(
            "PauseWorkflowCommand", "StopWorkflowCommand", "PauseNodeCommand");
  }

  @Test
  void nodeSelectorsLimitWhichFailuresCount() {
    final Map<String, Object> settings =
        Map.of("failures", 2, "nodes", List.of("deploy", "build.*"));

    assertThat(
            commands(
                guard(settings),
                failure("other", Map.of()),
                failure("other", Map.of()),
                failure("deploy", Map.of())))
        .isEmpty();
    assertThat(
            commands(guard(settings), failure("build.test", Map.of()), failure("deploy", Map.of())))
        .hasSize(1);
    assertThat(
            commands(guard(settings), failure("builder", Map.of()), failure("buildx.a", Map.of())))
        .isEmpty();
  }

  @Test
  void errorContainsLimitsFailuresToThoseWhoseErrorMetadataHasTheText() {
    final Map<String, Object> settings = Map.of("failures", 1, "errorContains", "time'out (1)");

    assertThat(commands(guard(settings), failure("a", Map.of()))).isEmpty();
    assertThat(commands(guard(settings), failure("a", Map.of("error", "boom")))).isEmpty();
    assertThat(
            commands(guard(settings), failure("a", Map.of("error", "read time'out (1) after 3s"))))
        .hasSize(1);
  }

  @Test
  void badSettingsAreRefusedWithTheKeyInTheMessage() {
    assertThatThrownBy(() -> guard(Map.of("failures", 0))).hasMessageContaining("failures");
    assertThatThrownBy(() -> guard(Map.of("withinSeconds", 1.5)))
        .hasMessageContaining("withinSeconds");
    assertThatThrownBy(() -> guard(Map.of("action", "EXPLODE"))).hasMessageContaining("action");
    assertThatThrownBy(() -> guard(Map.of("nodes", List.of("a,b")))).hasMessageContaining("nodes");
    assertThatThrownBy(() -> guard(Map.of("nodes", "a"))).hasMessageContaining("nodes");
    assertThatThrownBy(() -> guard(Map.of("errorContains", " ")))
        .hasMessageContaining("errorContains");
    assertThatThrownBy(() -> guard(Map.of("errorContains", 5)))
        .hasMessageContaining("errorContains");
  }

  @Test
  void catalogEntryIsCompleteAndItsExampleMakesGuard() {
    final var descriptor = factory.describe();

    assertThat(descriptor.displayName()).isEqualTo("Failure guard");
    assertThat(descriptor.category().name()).isEqualTo("GUARD");
    assertThat(descriptor.uiDesign()).isPresent();
    assertThat(factory.getConfigSchema())
        .extracting(ConfigField::name)
        .containsExactly("failures", "withinSeconds", "nodes", "errorContains", "action");
    factory.validate(new Supervisor("g", factory.type(), descriptor.exampleSettings(), false));
    StepVerifier.create(guard(descriptor.exampleSettings()).supervise(Flux.empty()))
        .verifyComplete();
  }
}
