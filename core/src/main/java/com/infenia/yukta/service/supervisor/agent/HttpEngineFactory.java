// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor.agent;

import com.infenia.yukta.plugin.control.engine.Directive;
import com.infenia.yukta.plugin.control.engine.EngineConfig;
import com.infenia.yukta.plugin.control.engine.EngineContext;
import com.infenia.yukta.plugin.control.engine.EngineDescriptor;
import com.infenia.yukta.plugin.control.engine.Signal;
import com.infenia.yukta.plugin.control.engine.SupervisorEngine;
import com.infenia.yukta.plugin.control.engine.SupervisorEngineFactory;
import com.infenia.yukta.plugin.core.ConfigField;
import java.util.List;
import java.util.Set;
import reactor.core.publisher.Flux;

/**
 * The {@code http} engine: an external program (deterministic or an AI) is the decision maker. It
 * connects to {@code /api/agent/v1} with the credential of the supervisor, streams the signals and
 * posts directives; the supervisor checks them like any engine's.
 */
public final class HttpEngineFactory implements SupervisorEngineFactory {

  /** The engine type. */
  public static final String HTTP_TYPE = "http";

  private final AgentHub hub;

  /**
   * Creates the factory.
   *
   * @param hub where the agent API meets the engines
   */
  public HttpEngineFactory(final AgentHub hub) {
    this.hub = hub;
  }

  @Override
  public String type() {
    return HTTP_TYPE;
  }

  @Override
  public EngineDescriptor describe() {
    return new EngineDescriptor(
        "Agent endpoint",
        "An application operates this supervisor through /api/agent/v1 with its own credential:"
            + " it streams the events and sends commands, answers reviews and delegations.",
        Set.of(
            Signal.Kind.EVENT,
            Signal.Kind.REVIEW,
            Signal.Kind.STARTED,
            Signal.Kind.DELEGATED,
            Signal.Kind.CONSULTED),
        true);
  }

  @Override
  public List<ConfigField> getConfigSchema() {
    return List.of();
  }

  @Override
  public void validate(final EngineConfig config) {
    // the engine has no settings: the credential is made with the agent API
  }

  @Override
  public SupervisorEngine create(final EngineConfig config, final EngineContext context) {
    final AgentHub.Channel channel = hub.open(context.self(), context::reads);
    return new SupervisorEngine() {
      @Override
      public Flux<Directive> run(final Flux<Signal> signals) {
        return Flux.merge(
            signals.doOnNext(channel::signal).thenMany(Flux.<Directive>empty()),
            channel.directives());
      }

      @Override
      public void close() {
        hub.close(context.self(), channel);
      }
    };
  }
}
