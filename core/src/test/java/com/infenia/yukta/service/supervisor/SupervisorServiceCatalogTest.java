// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.infenia.yukta.plugin.control.ControlPlanePlugin;
import com.infenia.yukta.plugin.control.ExecutionControlCommand;
import com.infenia.yukta.plugin.control.ExecutionEvent;
import com.infenia.yukta.plugin.control.ExecutionSupervisor;
import com.infenia.yukta.plugin.control.Supervisor;
import com.infenia.yukta.plugin.control.SupervisorFactory;
import com.infenia.yukta.plugin.core.ConfigField;
import com.infenia.yukta.service.log.InMemoryExecutionLogStore;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

/** What the service tells the catalog about a type, and how it checks a draft. */
@NoArgsConstructor
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
class SupervisorServiceCatalogTest {

  /** A url value the test factory refuses. */
  private static final String BAD = "bad";

  /** A control plane that does nothing. */
  private interface QuietPlane extends ControlPlanePlugin {
    @Override
    default Flux<ExecutionControlCommand> supervise(final Flux<ExecutionEvent> events) {
      return Flux.empty();
    }
  }

  private static SupervisorFactory factory(
      final String type, final Supplier<ExecutionSupervisor> made) {
    return new SupervisorFactory() {
      @Override
      public String type() {
        return type;
      }

      @Override
      public List<ConfigField> getConfigSchema() {
        return List.of(
            ConfigField.optional("url", ConfigField.Type.STRING, null, "where"),
            ConfigField.optional("bearer", ConfigField.Type.SECRET, null, "credential"));
      }

      @Override
      public Set<String> defaultAllowedCommands() {
        return Set.of("PauseWorkflowCommand");
      }

      @Override
      public ExecutionSupervisor create(final Supervisor supervisor) {
        if (BAD.equals(supervisor.settings().get("url"))) {
          throw new IllegalArgumentException("url is bad");
        }
        return made.get();
      }
    };
  }

  private static SupervisorFactory refusing(final String type) {
    return factory(
        type,
        () -> {
          throw new IllegalStateException("cannot be made here");
        });
  }

  private static SupervisorService service(final SupervisorFactory... factories) {
    return new SupervisorService(
        new InMemorySupervisorStore(),
        List.of(factories),
        mock(SupervisorHost.class),
        new InMemoryExecutionLogStore());
  }

  @Test
  void unknownTypeHasNothingToShow() {
    final SupervisorService service = service();

    assertThat(service.describe("nope")).isEmpty();
    assertThat(service.configSchema("nope")).isEmpty();
    assertThat(service.secretSettings("nope")).isEmpty();
    assertThat(service.kind("nope")).isEqualTo("SUPERVISOR");
    assertThat(service.defaultAllowedCommands("nope")).isNull();
  }

  @Test
  void knownTypeShowsItsSchemaSecretsAndDefaults() {
    final SupervisorService service = service(refusing("t"));

    assertThat(service.configSchema("t"))
        .extracting(ConfigField::name)
        .containsExactly("url", "bearer");
    assertThat(service.secretSettings("t")).containsExactly("bearer");
    assertThat(service.defaultAllowedCommands("t")).containsExactly("PauseWorkflowCommand");
    assertThat(service.describe("t")).isPresent();
  }

  @Test
  void kindComesFromWhatTheExampleMakes() {
    final SupervisorService service =
        service(
            factory("plane", () -> mock(QuietPlane.class)),
            factory("plain", () -> mock(ExecutionSupervisor.class)));

    assertThat(service.kind("plane")).isEqualTo("CONTROL_PLANE");
    assertThat(service.kind("plain")).isEqualTo("SUPERVISOR");
  }

  @Test
  void kindFallsBackToTheNameWhenTheExampleCannotBeMade() {
    final SupervisorService service =
        service(refusing("composite"), refusing("model-control-plane"), refusing("custom"));

    assertThat(service.kind("composite")).isEqualTo("COMPOSITE");
    assertThat(service.kind("model-control-plane")).isEqualTo("CONTROL_PLANE");
    assertThat(service.kind("custom")).isEqualTo("SUPERVISOR");
  }

  @Test
  void checkReportsNothingForGoodDraft() {
    final SupervisorService service = service(refusing("t"));

    StepVerifier.create(service.check(new Supervisor("s1", "t", Map.of("url", "ok"), false)))
        .assertNext(issues -> assertThat(issues).isEmpty())
        .verifyComplete();
  }

  @Test
  void checkReportsSettingsByFieldAndTypeProblemsUnderSettings() {
    final SupervisorService service = service(refusing("t"));

    StepVerifier.create(service.check(new Supervisor("s1", "t", Map.of("url", 1, "x", 2), false)))
        .assertNext(
            issues ->
                assertThat(issues)
                    .extracting(SettingsValidationException.Issue::field)
                    .contains("settings.x"))
        .verifyComplete();
    StepVerifier.create(service.check(new Supervisor("s1", "t", Map.of("url", "bad"), false)))
        .assertNext(
            issues ->
                assertThat(issues)
                    .containsExactly(
                        new SettingsValidationException.Issue("settings", "url is bad")))
        .verifyComplete();
    StepVerifier.create(service.check(new Supervisor("s1", "nope", Map.of(), false)))
        .assertNext(issues -> assertThat(issues).hasSize(1))
        .verifyComplete();
  }
}
