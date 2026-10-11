// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.controlplane.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.infenia.yukta.controlplane.model.ModelPlaneSettings.Command;
import com.infenia.yukta.controlplane.model.ModelPlaneSettings.Option;
import com.infenia.yukta.plugin.decision.ModelSpec;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/** Tests for ModelPlaneSettings defaults and validation. */
@NoArgsConstructor
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
class ModelPlaneSettingsTest {

  private static ModelPlaneSettings settings(
      final ModelSpec.Backend backend,
      final String provider,
      final String name,
      final String baseUrl,
      final String instructions,
      final Map<String, Option> options,
      final Set<String> events) {
    return new ModelPlaneSettings(
        backend,
        provider,
        name,
        baseUrl,
        null,
        Duration.ofSeconds(2),
        0.6,
        instructions,
        options,
        events);
  }

  @Test
  void defaults_areThePromptThatScoredWellAndPauseOnly() {
    final ModelPlaneSettings settings = settings(null, null, "nimble", " ", " ", null, null);

    assertThat(settings.backend()).isEqualTo(ModelSpec.Backend.SYSTEMONE);
    assertThat(settings.baseUrl()).isEqualTo("http://localhost:11434");
    assertThat(settings.instructions()).isEqualTo(ModelPlaneSettings.DEFAULT_INSTRUCTIONS);
    assertThat(settings.options()).containsOnlyKeys("continue", "pause");
    assertThat(settings.options().get("continue").command()).isEqualTo(Command.NONE);
    assertThat(settings.options().get("pause").command()).isEqualTo(Command.PAUSE);
    assertThat(settings.options().values()).noneMatch(option -> option.command() == Command.STOP);
    assertThat(settings.events())
        .containsExactlyInAnyOrder("NodeStatusChanged", "WorkflowStatusChanged");
  }

  @Test
  void configuredValues_areKept() {
    final Map<String, Option> options =
        Map.of("a", new Option("x", Command.NONE), "b", new Option("y", Command.STOP));

    final ModelPlaneSettings settings =
        settings(
            ModelSpec.Backend.SYSTEMONE,
            null,
            "nimble",
            "http://ollama:11434",
            "Act?",
            options,
            Set.of("LogLine"));

    assertThat(settings.baseUrl()).isEqualTo("http://ollama:11434");
    assertThat(settings.instructions()).isEqualTo("Act?");
    assertThat(settings.options()).containsOnlyKeys("a", "b");
    assertThat(settings.events()).containsExactly("LogLine");
  }

  @Test
  void emptyOptionsAndEvents_fallBackToDefaults() {
    final ModelPlaneSettings settings =
        settings(null, null, "nimble", null, null, Map.of(), Set.of());

    assertThat(settings.options()).containsOnlyKeys("continue", "pause");
    assertThat(settings.events()).contains("NodeStatusChanged");
  }

  @Test
  void binder_readsThePromptFromProperties() {
    final Map<String, String> properties =
        Map.of(
            "yukta.control-plane.model.name", "tev1:0.8b",
            "yukta.control-plane.model.instructions", "Should we act?",
            "yukta.control-plane.model.timeout", "5s",
            "yukta.control-plane.model.min-probability", "0.7",
            "yukta.control-plane.model.options.ok.description", "Fine",
            "yukta.control-plane.model.options.ok.command", "none",
            "yukta.control-plane.model.options.halt.description", "Broken",
            "yukta.control-plane.model.options.halt.command", "stop",
            "yukta.control-plane.model.events[0]", "LogLine");

    final ModelPlaneSettings settings =
        new Binder(new MapConfigurationPropertySource(properties))
            .bind("yukta.control-plane.model", ModelPlaneSettings.class)
            .get();

    assertThat(settings.name()).isEqualTo("tev1:0.8b");
    assertThat(settings.instructions()).isEqualTo("Should we act?");
    assertThat(settings.timeout()).isEqualTo(Duration.ofSeconds(5));
    assertThat(settings.minProbability()).isEqualTo(0.7);
    assertThat(settings.options())
        .containsEntry("ok", new Option("Fine", Command.NONE))
        .containsEntry("halt", new Option("Broken", Command.STOP));
    assertThat(settings.events()).containsExactly("LogLine");
  }

  @Test
  void binder_nameAloneGivesTheDefaults() {
    final ModelPlaneSettings settings =
        new Binder(
                new MapConfigurationPropertySource(Map.of("yukta.control-plane.model.name", "m")))
            .bind("yukta.control-plane.model", ModelPlaneSettings.class)
            .get();

    assertThat(settings.timeout()).isEqualTo(Duration.ofSeconds(2));
    assertThat(settings.minProbability()).isEqualTo(0.6);
    assertThat(settings.options()).containsOnlyKeys("continue", "pause");
  }

  @Test
  void chatBackend_keepsTheProviderDefaultEndpoint() {
    assertThat(settings(ModelSpec.Backend.LLM, "openai", "gpt", null, null, null, null).baseUrl())
        .isNull();
  }

  @Test
  void invalidSettings_areRejected() {
    assertThatThrownBy(() -> settings(null, null, null, null, null, null, null))
        .hasMessage("The model name is required");
    assertThatThrownBy(() -> settings(null, null, " ", null, null, null, null))
        .hasMessage("The model name is required");
    assertThatThrownBy(() -> settings(ModelSpec.Backend.LLM, null, "m", null, null, null, null))
        .hasMessage("A provider is required for backend LLM");
    assertThatThrownBy(() -> settings(ModelSpec.Backend.LLM, " ", "m", null, null, null, null))
        .hasMessage("A provider is required for backend LLM");
    assertThatThrownBy(
            () ->
                settings(
                    null,
                    null,
                    "m",
                    null,
                    null,
                    Map.of("only", new Option("one", Command.NONE)),
                    null))
        .hasMessage("At least two options are needed");
  }
}
