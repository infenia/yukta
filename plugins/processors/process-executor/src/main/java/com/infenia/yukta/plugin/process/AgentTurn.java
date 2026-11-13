// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.process;

import com.infenia.yukta.message.Message;
import com.infenia.yukta.plugin.core.PluginLogSink;
import com.infenia.yukta.plugin.core.WorkflowContext;
import com.infenia.yukta.plugin.input.InputGateway;
import com.infenia.yukta.plugin.input.InputIdentity;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

/** Starts the harness for one message and reads how it ended, with approvals when asked for. */
final class AgentTurn {

  private final ProcessExecutorGateway gateway;
  private final ObjectProvider<InputGateway> inputs;
  private final ObjectMapper json;

  /**
   * What one run needs.
   *
   * @param spec the process to start
   * @param preset the harness
   * @param talk how it converses, or null for a one-shot run
   * @param approvals risk, time to answer and who answers; null for a one-shot run
   * @param prompt what is asked
   * @param timeoutSeconds the process timeout, for the outcome
   */
  /* default */ record Plan(
      ProcessExecutionSpec spec,
      AgentPreset preset,
      AgentPreset.@Nullable Interactive talk,
      AgentApprovals.@Nullable Settings approvals,
      String prompt,
      long timeoutSeconds) {}

  /* default */ AgentTurn(
      final ProcessExecutorGateway gateway,
      final ObjectProvider<InputGateway> inputs,
      final ObjectMapper json) {
    this.gateway = gateway;
    this.inputs = inputs;
    this.json = json;
  }

  /** Runs the harness; emits the outcome map when it ends. */
  /* default */ Mono<Map<String, Object>> run(final Message<?> message, final Plan plan) {
    return Mono.deferContextual(
        ctx -> {
          final AgentOutput output =
              new AgentOutput(
                  plan.preset().output(),
                  plan.talk(),
                  ctx.<PluginLogSink>getOrEmpty(WorkflowContext.LOG_SINK_KEY)
                      .orElse(PluginLogSink.NOOP),
                  json);
          final Mono<ProcessExecutionResult> ended =
              plan.talk() == null
                  ? gateway.executeForResult(plan.spec(), output)
                  : converse(message, plan, plan.talk(), output, InputIdentity.from(ctx));
          return ended.map(r -> output.outcome(r.exitCode(), r.timedOut(), plan.timeoutSeconds()));
        });
  }

  private Mono<ProcessExecutionResult> converse(
      final Message<?> message,
      final Plan plan,
      final AgentPreset.Interactive talk,
      final AgentOutput output,
      final InputIdentity who) {
    final AtomicReference<AgentApprovals> approvals = new AtomicReference<>();
    return gateway
        .executeInteractive(
            plan.spec(),
            output,
            stdin -> {
              final AgentApprovals a =
                  new AgentApprovals(
                      talk,
                      plan.approvals(),
                      json,
                      inputs::getIfAvailable,
                      who,
                      message,
                      plan.preset().id(),
                      stdin);
              approvals.set(a);
              output.converse(a::handle, stdin::close);
              stdin.write(AgentApprovals.userMessage(talk.userMessage(), plan.prompt(), json));
            })
        .doFinally(signal -> Mono.justOrEmpty(approvals.get()).subscribe(AgentApprovals::dispose));
  }
}
