// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor.agent;

import com.infenia.yukta.plugin.control.SupervisorCategory;
import com.infenia.yukta.plugin.control.engine.Directive;
import com.infenia.yukta.plugin.control.engine.EngineConfig;
import com.infenia.yukta.plugin.control.engine.EngineContext;
import com.infenia.yukta.plugin.control.engine.EngineDescriptor;
import com.infenia.yukta.plugin.control.engine.Signal;
import com.infenia.yukta.plugin.control.engine.SupervisorEngine;
import com.infenia.yukta.plugin.control.engine.SupervisorEngineFactory;
import com.infenia.yukta.plugin.core.ConfigField;
import com.infenia.yukta.plugin.core.ConfigField.Type;
import com.infenia.yukta.plugin.core.UiDesign;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import reactor.core.publisher.Flux;

/**
 * The {@code http} engine: an external system (deterministic or an AI) is the decision maker. It
 * reads the signals over {@code /api/agent/v1} with the credential of the supervisor (stream or
 * poll), or gets each one posted to its {@code url} ({@link Outbound}), and decides through the
 * same API: directives, review answers, delegation answers. The supervisor checks them like any
 * engine's.
 */
public final class HttpEngineFactory implements SupervisorEngineFactory {

  /** The engine type. */
  public static final String HTTP_TYPE = "http";

  private final AgentHub hub;

  private final Outbound outbound;

  /**
   * Creates the factory.
   *
   * @param hub where the agent API meets the engines
   * @param outbound posts the signals to a supervisor's {@code url}
   */
  public HttpEngineFactory(final AgentHub hub, final Outbound outbound) {
    this.hub = hub;
    this.outbound = outbound;
  }

  @Override
  public String type() {
    return HTTP_TYPE;
  }

  @Override
  public EngineDescriptor describe() {
    return new EngineDescriptor(
        "External system",
        "An external system, often an AI agent, decides. It gets what happens on the owned nodes"
            + " posted to its url, or reads the same over /api/agent/v1 with the supervisor's"
            + " credential, and sends commands, review answers and delegation answers there.",
        Set.of(
            Signal.Kind.EVENT,
            Signal.Kind.REVIEW,
            Signal.Kind.STARTED,
            Signal.Kind.DELEGATED,
            Signal.Kind.CONSULTED),
        true,
        SupervisorCategory.EXTERNAL_SYSTEM,
        "Use it when an AI agent or a service you run decides. Set url to have each signal"
            + " posted to it, or let it read them over /api/agent/v1; either way it decides there,"
            + " with the credential you issue on the supervisor.",
        Optional.of(new UiDesign("orange", "api", "External system", 140, 80)),
        Map.of(Outbound.URL, "https://agent.example.com/yukta/signals"));
  }

  @Override
  public List<ConfigField> getConfigSchema() {
    return List.of(
        ConfigField.optional(
            Outbound.URL,
            Type.STRING,
            null,
            "Where to post each signal; without it the system reads them over the agent API"),
        ConfigField.optional(
            Outbound.HEADERS, Type.MAP, null, "Headers to send; a value may be ${env.NAME}"),
        ConfigField.optional(
            Outbound.SECRET_HEADERS, Type.SECRET, null, "Headers with secrets; never shown again"),
        ConfigField.optional(
            Outbound.TIMEOUT,
            Type.NUMBER,
            3000,
            "How long one post may take, at most " + Outbound.MAX_TIMEOUT_MS),
        ConfigField.optional(
            Outbound.MAX_CONCURRENT, Type.NUMBER, 4, "Most posts at once, at most 32"));
  }

  @Override
  public void validate(final EngineConfig config) {
    outbound.read(config.settings());
  }

  @Override
  public SupervisorEngine create(final EngineConfig config, final EngineContext context) {
    final Outbound.@Nullable Config posting = outbound.read(config.settings());
    final AgentHub.Lease lease = hub.lease(context.self(), context::reads);
    final AgentHub.Channel channel = lease.channel();
    return new SupervisorEngine() {
      @Override
      public Flux<Directive> run(final Flux<Signal> signals) {
        // listen before the first signal arrives, so the post carries the cursor the API shows
        final Flux<Directive> posts =
            posting == null
                ? Flux.empty()
                : outbound
                    .deliver(context.self(), posting, channel.signalsAfter(-1))
                    .thenMany(Flux.empty());
        return Flux.merge(
            posts,
            signals.doOnNext(channel::signal).thenMany(Flux.<Directive>empty()),
            channel.directives().filterWhen(lease::takes));
      }

      @Override
      public void close() {
        lease.close();
      }
    };
  }
}
