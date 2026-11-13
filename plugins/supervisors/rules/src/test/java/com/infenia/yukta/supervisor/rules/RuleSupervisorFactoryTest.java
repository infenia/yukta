// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.supervisor.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.infenia.yukta.plugin.control.ExecutionSupervisor;
import com.infenia.yukta.plugin.control.Supervisor;
import com.infenia.yukta.plugin.control.SupervisorCategory;
import com.infenia.yukta.plugin.control.SupervisorDescriptor;
import java.util.List;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.aot.hint.BindingReflectionHintsRegistrar;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.annotation.RegisterReflectionForBinding;
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates;

/** Tests for the behaviour described by {@link RuleSupervisorFactory}. */
@NoArgsConstructor
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
class RuleSupervisorFactoryTest {

  private final RuleSupervisorFactory factory = new RuleSupervisorFactory();

  @Test
  void makesRuleSupervisorFromTheRulesInTheSettings() {
    final Map<String, Object> settings =
        Map.of(
            "rules",
            List.of(
                Map.of(
                    "id", "pause",
                    "when", Map.of("event", "NodeStatusChanged", "status", "FAILURE"),
                    "then", Map.of("command", "PauseWorkflow"))));

    final ExecutionSupervisor supervisor =
        factory.create(new Supervisor("p", RuleSupervisorFactory.PROFILE_TYPE, settings, false));

    assertThat(factory.type()).isEqualTo("rules");
    assertThat(supervisor).isInstanceOf(RuleSupervisor.class);
  }

  @Test
  void capabilitySettingsSitNextToTheRulesAndSilenceTheAnswerWarning() {
    final List<Object> rules =
        List.of(
            Map.of(
                "id", "approve",
                "when", Map.of("event", "InputRequested"),
                "then", Map.of("answer", Map.of("decision", "approve"))));

    final RuleSupervisor allowed =
        (RuleSupervisor)
            factory.create(
                new Supervisor(
                    "p",
                    RuleSupervisorFactory.PROFILE_TYPE,
                    Map.of("rules", rules, "canAnswer", true, "maxRisk", "MEDIUM"),
                    false));
    final RuleSupervisor notAllowed =
        (RuleSupervisor)
            factory.create(
                new Supervisor(
                    "p",
                    RuleSupervisorFactory.PROFILE_TYPE,
                    Map.of("rules", rules, "allowedCommands", List.of("EscalateInputCommand")),
                    false));

    assertThat(allowed.warning()).isEmpty();
    assertThat(notAllowed.warning()).isPresent();
  }

  @Test
  void describesItselfAndDeclaresTheRuleSettings() {
    final SupervisorDescriptor described = factory.describe();

    assertThat(described.displayName()).isEqualTo("Rules");
    assertThat(described.category()).isEqualTo(SupervisorCategory.RULES);
    assertThat(factory.canInitiate()).isTrue();
    assertThat(factory.signals())
        .contains(
            com.infenia.yukta.plugin.control.engine.Signal.Kind.TICK,
            com.infenia.yukta.plugin.control.engine.Signal.Kind.REVIEW);
    assertThat(described.uiDesign()).isPresent();
    assertThat(described.exampleSettings()).containsKey("rules");
    assertThat(factory.getConfigSchema()).isEqualTo(RuleSupervisor.SCHEMA);
  }

  @Test
  void invalidRuleOrMisspelledFieldIsRefused() {
    final Supervisor misspelled =
        new Supervisor("p", RuleSupervisorFactory.PROFILE_TYPE, Map.of("rulez", List.of()), false);
    final Supervisor noId =
        new Supervisor(
            "p",
            RuleSupervisorFactory.PROFILE_TYPE,
            Map.of("rules", List.of(Map.of("when", Map.of("status", "FAILURE")))),
            false);

    assertThatThrownBy(() -> factory.create(misspelled))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> factory.create(noId)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void ruleRecordsAreBindableInANativeImage() {
    final RegisterReflectionForBinding hint =
        RuleSupervisorFactory.class.getAnnotation(RegisterReflectionForBinding.class);
    final RuntimeHints hints = new RuntimeHints();

    new BindingReflectionHintsRegistrar().registerReflectionHints(hints.reflection(), hint.value());

    // the top record and a nested one: Jackson reads their components in the native binary
    for (final Class<?> type : List.of(RuleSet.class, RuleSet.Deny.class, RuleSet.When.class)) {
      assertThat(
              RuntimeHintsPredicates.reflection()
                  .onType(type)
                  .withMemberCategory(MemberCategory.INVOKE_DECLARED_CONSTRUCTORS))
          .accepts(hints);
    }
  }
}
