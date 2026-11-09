// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

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
import reactor.test.StepVerifier;

/** What the service tells the catalog about a type, and how it checks a draft. */
@NoArgsConstructor
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
class SupervisorServiceCatalogTest {

  /** A url value the test factory refuses. */
  private static final String BAD = "bad";

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
  void checkReportsNothingForGoodDraft() {
    final SupervisorService service = service(factory("t", () -> mock(ExecutionSupervisor.class)));

    StepVerifier.create(service.check(new Supervisor("s1", "t", Map.of("url", "ok"), false)))
        .assertNext(issues -> assertThat(issues).isEmpty())
        .verifyComplete();
  }

  @Test
  void checkReportsSettingsByFieldAndTypeProblemsUnderSettings() {
    final SupervisorService service = service(factory("t", () -> mock(ExecutionSupervisor.class)));

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

  @Test
  void checkAlsoAsksTheWorkflowChecksWhenThereAreSome() {
    final SupervisorService service = service(factory("t", () -> mock(ExecutionSupervisor.class)));
    final com.infenia.yukta.service.supervisor.workflow.WorkflowSupervisorChecks checks =
        mock(com.infenia.yukta.service.supervisor.workflow.WorkflowSupervisorChecks.class);
    org.mockito.Mockito.when(checks.check(org.mockito.ArgumentMatchers.any()))
        .thenReturn(
            reactor.core.publisher.Mono.error(new IllegalArgumentException("no such session")));
    service.setWorkflowChecks(checks);

    StepVerifier.create(service.check(new Supervisor("s1", "t", Map.of(), false)))
        .assertNext(
            issues ->
                assertThat(issues)
                    .containsExactly(
                        new SettingsValidationException.Issue("settings", "no such session")))
        .verifyComplete();
  }

  @Test
  void supervisorThatWantsItsAttachmentIdIsToldBeforeItStarts() {
    final ExecutionSupervisor aware =
        mock(
            ExecutionSupervisor.class,
            org.mockito.Mockito.withSettings()
                .extraInterfaces(com.infenia.yukta.plugin.control.AttachmentAware.class));
    final SupervisorService service = service(factory("t", () -> aware));
    service.saveSupervisor(new Supervisor("s1", "t", Map.of(), false)).block();

    service
        .attach(
            new com.infenia.yukta.plugin.control.SupervisorAttachment(
                "att-1", "s1", new com.infenia.yukta.plugin.control.SupervisorScope.Global(), true))
        .block();

    org.mockito.Mockito.verify((com.infenia.yukta.plugin.control.AttachmentAware) aware)
        .bindTo("att-1");
  }
}
