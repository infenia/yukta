// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.input.route;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.infenia.yukta.config.YuktaProperties;
import com.infenia.yukta.testsupport.YuktaSettings;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

/** The checks a tier and a route make, and how a route is read from the settings. */
@NoArgsConstructor
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
class RouteModelTest {

  private static Tier tier(final String name, final List<String> responders) {
    return new Tier(name, responders, null, null, 0, 0, null, null);
  }

  @Test
  void tierFillsInItsModeTypeAndSettings() {
    final Tier supervisors = tier("auto", List.of("supervisor:*"));
    assertThat(supervisors.mode()).isEqualTo(Mode.FIRST);
    assertThat(supervisors.type()).isEqualTo(ResponderType.AUTO);
    assertThat(supervisors.settings()).isEmpty();
    assertThat(supervisors.votes()).isFalse();

    assertThat(tier("ops", List.of("role:OPERATOR")).type()).isEqualTo(ResponderType.HUMAN);
    assertThat(tier("mixed", List.of("supervisor:*", "external:webhook:ops")).type())
        .isEqualTo(ResponderType.HUMAN);
    assertThat(
            new Tier("v", List.of("supervisor:*"), null, Mode.UNANIMOUS, 0, 0, null, Map.of("a", 1))
                .votes())
        .isTrue();
  }

  @Test
  void settingsWithoutTiersAreRefusedWhenNamed() {
    assertThatThrownBy(() -> new RouteSettings(null, null).named("r"))
        .hasMessageContaining("at least one tier");
  }

  @Test
  void tierRefusesWhatItCannotUse() {
    assertThatThrownBy(() -> tier("bad name", List.of("supervisor:*")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> tier("t", List.of())).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> tier("t", null)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> tier("t", List.of("everybody")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new Tier("t", List.of("supervisor:*"), Duration.ZERO, null, 0, 0, null, null))
        .hasMessageContaining("timeout must be positive");
    assertThatThrownBy(
            () ->
                new Tier(
                    "t", List.of("supervisor:*"), Duration.ofSeconds(-1), null, 0, 0, null, null))
        .hasMessageContaining("timeout must be positive");
    assertThatThrownBy(
            () -> new Tier("t", List.of("supervisor:*"), null, Mode.QUORUM, 1, 0, null, null))
        .hasMessageContaining("quorum must be at least 2");
    assertThatThrownBy(() -> new Tier("t", List.of("supervisor:*"), null, null, 0, 1.5, null, null))
        .hasMessageContaining("minConfidence");
    assertThatThrownBy(
            () -> new Tier("t", List.of("supervisor:*"), null, null, 0, -0.1, null, null))
        .hasMessageContaining("minConfidence");
  }

  @Test
  void routeNeedsTierAndAnEndForItsLastOne() {
    assertThatThrownBy(() -> new InputRoute("r", List.of(), null))
        .hasMessageContaining("at least one tier");
    assertThatThrownBy(() -> new InputRoute("r", null, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new InputRoute("r", List.of(tier("auto", List.of("supervisor:*"))), null))
        .hasMessageContaining("on-final-timeout");

    assertThat(
            new InputRoute("r", List.of(tier("auto", List.of("supervisor:*"))), FinalAction.FAIL))
        .isNotNull();
    assertThat(new InputRoute("r", List.of(tier("ops", List.of("role:OPERATOR"))), null))
        .isNotNull();
  }

  @Test
  void routesAreReadFromTheSettingsAndCheckedAtStartUp() {
    final YuktaProperties properties =
        YuktaSettings.bind(
            Map.of(
                "yukta.input.routes.default.tiers[0].name", "auto",
                "yukta.input.routes.default.tiers[0].responders[0]", "supervisor:*",
                "yukta.input.routes.default.tiers[0].timeout", "30s",
                "yukta.input.routes.default.tiers[0].min-confidence", "0.8",
                "yukta.input.routes.default.tiers[1].name", "ops",
                "yukta.input.routes.default.tiers[1].responders[0]", "external:webhook:ops",
                "yukta.input.routes.default.on-final-timeout", "FAIL"));

    final RouteSettings settings = properties.input().routes().get("default");

    assertThat(settings.tiers()).extracting(Tier::name).containsExactly("auto", "ops");
    assertThat(settings.tiers().getFirst().timeout()).isEqualTo(Duration.ofSeconds(30));
    assertThat(settings.tiers().getFirst().minConfidence()).isEqualTo(0.8);
    assertThat(settings.onFinalTimeout()).isEqualTo(FinalAction.FAIL);
    assertThat(settings.named("default").name()).isEqualTo("default");

    assertThatThrownBy(
            () ->
                YuktaSettings.bind(
                    Map.of(
                        "yukta.input.routes.bad.tiers[0].name", "auto",
                        "yukta.input.routes.bad.tiers[0].responders[0]", "supervisor:*")))
        .hasRootCauseMessage(
            "Route bad: the last tier must be of type HUMAN or the route needs on-final-timeout");
  }

  @Test
  void theResolverReadsTheRoutesOfTheSettingsAndKnowsTheirNames() {
    final YuktaProperties properties =
        YuktaSettings.bind(
            Map.of(
                "yukta.input.routes.ops.tiers[0].name", "ops",
                "yukta.input.routes.ops.tiers[0].responders[0]", "role:OPERATOR"));
    @SuppressWarnings("unchecked")
    final org.springframework.beans.factory.ObjectProvider<
            com.infenia.yukta.service.session.store.SessionConfigStore>
        sessions = org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class);

    final RouteResolver resolver = new RouteResolver(properties, sessions);

    assertThat(resolver.knows("ops")).isTrue();
    assertThat(resolver.knows("other")).isFalse();
  }
}
