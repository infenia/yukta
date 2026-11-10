// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.control.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.infenia.yukta.plugin.control.Supervisor;
import com.infenia.yukta.plugin.control.SupervisorPreset;
import com.infenia.yukta.plugin.core.ConfigField;
import com.infenia.yukta.plugin.input.InputRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

/** What an engine reaches: its context, its reads and the presets of its type. */
class EngineContextTest {

  /** A factory that keeps the context it was given, so the test can use it. */
  private static SupervisorEngineFactory capturing(final AtomicReference<EngineContext> seen) {
    return new SupervisorEngineFactory() {
      @Override
      public String type() {
        return "t";
      }

      @Override
      public EngineDescriptor describe() {
        return new EngineDescriptor("T", "test", Set.of(Signal.Kind.EVENT), false);
      }

      @Override
      public List<ConfigField> getConfigSchema() {
        return List.of();
      }

      @Override
      public void validate(final EngineConfig config) {
        // anything goes
      }

      @Override
      public SupervisorEngine create(final EngineConfig config, final EngineContext context) {
        seen.set(context);
        return signals -> Flux.never();
      }
    };
  }

  @Test
  void nothingIsReadBeforeTheSupervisorIsAttached() {
    StepVerifier.create(EngineReads.NONE.executions(null, 5)).verifyComplete();
    StepVerifier.create(EngineReads.NONE.openInputs(5)).verifyComplete();
    final EngineContext bare =
        new EngineContext() {
          @Override
          public String self() {
            return "s";
          }

          @Override
          public Flux<Instant> ticks(final Duration period) {
            return Flux.empty();
          }

          @Override
          public Map<String, Object> state() {
            return Map.of();
          }
        };
    assertThat(bare.reads()).isSameAs(EngineReads.NONE);
    assertThat(new EngineReads.RunView("s", "e", "w", "RUNNING", null).executionId())
        .isEqualTo("e");
  }

  @Test
  void theFactoryGivesTheEngineItsNameStateTicksAndTheReadsOfTheAttachment() {
    final AtomicReference<EngineContext> seen = new AtomicReference<>();
    final EngineSupervisorFactory factory = new EngineSupervisorFactory(capturing(seen));
    final var made = factory.create(new Supervisor("sup", "t", Map.of(), false));
    final EngineContext context = seen.get();

    assertThat(factory.presets()).isEmpty();
    assertThat(context.self()).isEqualTo("sup");
    context.state().put("k", 1);
    assertThat(context.state()).containsEntry("k", 1);
    StepVerifier.create(context.ticks(Duration.ofMillis(1)).take(1))
        .expectNextCount(1)
        .verifyComplete();

    final EngineReads.RunView run = new EngineReads.RunView("s", "e1", "w", "RUNNING", null);
    ((ReadsAware) made)
        .readsFrom(
            new EngineReads() {
              @Override
              public Flux<RunView> executions(final String status, final int limit) {
                return Flux.just(run);
              }

              @Override
              public Flux<InputRequest> openInputs(final int limit) {
                return Flux.empty();
              }
            });
    StepVerifier.create(context.reads().executions(null, 1)).expectNext(run).verifyComplete();
    StepVerifier.create(context.reads().openInputs(1)).verifyComplete();
    StepVerifier.create(context.reads().held()).verifyComplete();
  }

  @Test
  void presetKeepsCopyAndNeedsNameAndDescription() {
    final Map<String, Object> settings =
        new java.util.concurrent.ConcurrentHashMap<>(Map.of("a", 1));
    final SupervisorPreset preset = new SupervisorPreset("p", "d", settings);
    settings.put("b", 2);
    assertThat(preset.settings()).containsOnlyKeys("a");
    assertThatThrownBy(() -> preset.settings().put("c", 3))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> new SupervisorPreset(null, "d", Map.of()))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new SupervisorPreset("p", null, Map.of()))
        .isInstanceOf(NullPointerException.class);
  }
}
