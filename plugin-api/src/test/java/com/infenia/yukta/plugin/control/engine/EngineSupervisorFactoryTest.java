// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.control.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.plugin.control.SupervisorCategory;
import com.infenia.yukta.plugin.core.ConfigField;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

class EngineSupervisorFactoryTest {

  private static final String ENGINE = "Rules";

  private static EngineSupervisorFactory factory(final EngineDescriptor descriptor) {
    return new EngineSupervisorFactory(
        new SupervisorEngineFactory() {
          @Override
          public String type() {
            return "rules";
          }

          @Override
          public EngineDescriptor describe() {
            return descriptor;
          }

          @Override
          public List<ConfigField> getConfigSchema() {
            return List.of();
          }

          @Override
          public void validate(final EngineConfig config) {
            // every setting is accepted
          }

          @Override
          public SupervisorEngine create(final EngineConfig config, final EngineContext context) {
            return signals -> Flux.empty();
          }
        });
  }

  @Test
  void signalsAreWhatTheEngineDrivesBy() {
    final EngineSupervisorFactory factory =
        factory(
            new EngineDescriptor(
                ENGINE, "d", Set.of(Signal.Kind.EVENT, Signal.Kind.REVIEW), false));

    assertThat(factory.signals()).containsExactlyInAnyOrder(Signal.Kind.EVENT, Signal.Kind.REVIEW);
  }

  @Test
  void canInitiateIsWhatTheEngineSays() {
    assertThat(factory(new EngineDescriptor(ENGINE, "d", Set.of(), true)).canInitiate()).isTrue();
    assertThat(factory(new EngineDescriptor(ENGINE, "d", Set.of(), false)).canInitiate()).isFalse();
  }

  @Test
  void describeShowsTheEngineAndKeepsItsTypeName() {
    final EngineSupervisorFactory factory =
        factory(
            new EngineDescriptor(
                ENGINE,
                "decides by rules",
                Set.of(Signal.Kind.EVENT),
                false,
                SupervisorCategory.ON_THIS_SERVER,
                "use it",
                Optional.empty(),
                Map.of("k", "v")));

    assertThat(factory.type()).isEqualTo("rules");
    assertThat(factory.describe().displayName()).isEqualTo(ENGINE);
    assertThat(factory.describe().description()).isEqualTo("decides by rules");
    assertThat(factory.describe().usagePattern()).isEqualTo("use it");
    assertThat(factory.describe().exampleSettings()).containsEntry("k", "v");
  }
}
