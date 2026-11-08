// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.control.engine;

import com.infenia.yukta.plugin.control.ExecutionSupervisor;
import com.infenia.yukta.plugin.control.Supervisor;
import com.infenia.yukta.plugin.control.SupervisorCategory;
import com.infenia.yukta.plugin.control.SupervisorDescriptor;
import com.infenia.yukta.plugin.control.SupervisorFactory;
import com.infenia.yukta.plugin.control.SupervisorScope;
import com.infenia.yukta.plugin.core.ConfigField;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import reactor.core.publisher.Flux;

/**
 * Offers a {@link SupervisorEngineFactory} as a {@link SupervisorFactory}, so an engine is saved,
 * attached and hosted like every other supervisor type. Register one bean per engine.
 */
public final class EngineSupervisorFactory implements SupervisorFactory {

  private final SupervisorEngineFactory engines;

  /**
   * Wraps an engine factory.
   *
   * @param engines the engine factory
   */
  public EngineSupervisorFactory(final SupervisorEngineFactory engines) {
    this.engines = engines;
  }

  @Override
  public String type() {
    return engines.type();
  }

  @Override
  public void validate(final Supervisor profile) {
    engines.validate(new EngineConfig(profile.settings()));
  }

  @Override
  public ExecutionSupervisor create(final Supervisor profile) {
    final Map<String, Object> state = new ConcurrentHashMap<>();
    final EngineContext context =
        new EngineContext() {
          @Override
          public String self() {
            return profile.id();
          }

          @Override
          public Flux<Instant> ticks(final Duration period) {
            return Flux.interval(period).map(n -> Instant.now());
          }

          @Override
          public Map<String, Object> state() {
            return state;
          }
        };
    return new EngineBackedSupervisor(
        profile.id(),
        new SupervisorScope.Global(),
        engines.create(new EngineConfig(profile.settings()), context),
        engines.describe().drivers().contains(Signal.Kind.REVIEW));
  }

  @Override
  public SupervisorDescriptor describe() {
    final EngineDescriptor engine = engines.describe();
    return new SupervisorDescriptor(
        engine.name(),
        engine.description(),
        "",
        SupervisorCategory.INTEGRATION,
        Optional.empty(),
        Map.of());
  }

  @Override
  public List<ConfigField> getConfigSchema() {
    return engines.getConfigSchema();
  }
}
