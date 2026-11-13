// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.infenia.yukta.plugin.control.SupervisorCommonSettings;
import com.infenia.yukta.plugin.control.SupervisorFactory;
import com.infenia.yukta.plugin.core.ConfigField;
import com.infenia.yukta.plugin.util.EgressPolicy;
import com.infenia.yukta.plugin.util.HostAllowList;
import com.infenia.yukta.service.supervisor.SupervisorSettingsValidator;
import com.infenia.yukta.service.supervisor.workflow.WorkflowSupervisorFactory;
import com.infenia.yukta.supervisor.rules.RuleSupervisorFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.json.JsonMapper;

/** Every shipped supervisor type describes itself, and what ships as an example validates. */
@NoArgsConstructor
class SupervisorCatalogTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();

  private static List<SupervisorFactory> factories() {
    return List.of(
        new RuleSupervisorFactory(),
        new com.infenia.yukta.plugin.control.engine.EngineSupervisorFactory(
            new com.infenia.yukta.service.supervisor.agent.HttpEngineFactory(
                new com.infenia.yukta.service.supervisor.agent.AgentHub(),
                new com.infenia.yukta.service.supervisor.agent.Outbound(
                    new com.infenia.yukta.service.supervisor.agent.Egress(
                        EgressPolicy.OPEN, HostAllowList.from(null)),
                    org.springframework.web.reactive.function.client.WebClient.create(),
                    JSON,
                    name -> null))),
        new com.infenia.yukta.plugin.control.engine.EngineSupervisorFactory(
            new com.infenia.yukta.supervisor.process.ProcessEngineFactory(
                java.util.Set.of(), true)),
        new WorkflowSupervisorFactory(null, null, null));
  }

  private static Stream<SupervisorFactory> allFactories() {
    return factories().stream();
  }

  @ParameterizedTest
  @MethodSource("allFactories")
  void descriptorIsFilledIn(final SupervisorFactory factory) {
    final var descriptor = factory.describe();

    assertThat(descriptor.displayName()).isNotBlank();
    assertThat(descriptor.description()).isNotBlank();
    assertThat(descriptor.usagePattern()).isNotBlank();
    assertThat(descriptor.uiDesign()).isPresent();
    assertThat(descriptor.exampleSettings()).isNotEmpty();
  }

  @ParameterizedTest
  @MethodSource("allFactories")
  void schemaHasUniqueNamesApartFromTheCommonOnes(final SupervisorFactory factory) {
    final List<String> names = factory.getConfigSchema().stream().map(ConfigField::name).toList();

    assertThat(names).isNotEmpty().doesNotHaveDuplicates();
    assertThat(names).doesNotContainAnyElementsOf(SupervisorCommonSettings.KEYS);
  }

  @ParameterizedTest
  @MethodSource("allFactories")
  void exampleSettingsPassTheSchema(final SupervisorFactory factory) {
    assertThatCode(
            () ->
                SupervisorSettingsValidator.check(
                    factory.describe().exampleSettings(), factory.getConfigSchema()))
        .doesNotThrowAnyException();
  }

  @Test
  void ruleSupervisorExampleFileValidatesAsSettings() throws Exception {
    final Map<String, Object> rules = read("plugins/supervisors/rules/rules.example.json");

    assertThatCode(
            () ->
                SupervisorSettingsValidator.check(
                    rules, new RuleSupervisorFactory().getConfigSchema()))
        .doesNotThrowAnyException();
  }

  @ParameterizedTest
  @MethodSource("profileFiles")
  void exampleProfilesValidate(final String file) throws Exception {
    final Map<String, Object> profile = read(file);
    final String type = (String) profile.get("type");
    @SuppressWarnings("unchecked")
    final Map<String, Object> settings = (Map<String, Object>) profile.get("settings");
    final SupervisorFactory factory =
        factories().stream().filter(f -> f.type().equals(type)).findFirst().orElseThrow();

    assertThatCode(() -> SupervisorSettingsValidator.check(settings, factory.getConfigSchema()))
        .doesNotThrowAnyException();
  }

  private static Stream<String> profileFiles() {
    return Stream.of(
        "examples/16-supervisor-profiles/input-requests.json",
        "examples/17-supervisor-workflow/supervisor.json",
        "examples/17-supervisor-workflow/section-supervisor.json");
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> read(final String file) throws java.io.IOException {
    return JSON.readValue(Files.readString(ROOT.resolve(file)), Map.class);
  }
}
