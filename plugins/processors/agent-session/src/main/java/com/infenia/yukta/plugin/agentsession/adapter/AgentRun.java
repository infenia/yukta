// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.agentsession.adapter;

import com.infenia.yukta.message.Message;
import com.infenia.yukta.plugin.agent.AgentConversationHandle;
import com.infenia.yukta.plugin.agentsession.AgentPreset;
import com.infenia.yukta.plugin.core.PluginLogSink;
import com.infenia.yukta.plugin.input.InputIdentity;
import com.infenia.yukta.plugin.process.ProcessExecutionSpec;
import java.time.Duration;
import org.jspecify.annotations.Nullable;

/**
 * What an adapter needs to run an agent for one message.
 *
 * @param message the message being worked on
 * @param preset the harness
 * @param spec the process to start
 * @param prompt what is asked
 * @param talk how the harness converses over stdin, or null for a one-shot run
 * @param approvals risk, time to answer and who answers; null when approvals are off
 * @param timeoutSeconds the process timeout, for the outcome
 * @param handle where the run records what happens; a handle that drops everything when
 *     conversations are off
 * @param log the run log of the node
 * @param who the run and node, or null when the run has no identity (a test, a direct call)
 * @param chat true when the node stays open for more messages ({@code mode: CONVERSATION}), false
 *     when it ends with the first turn
 * @param idleTimeout how long a conversation may wait for a message
 * @param heartbeat tells the engine the node is alive
 * @param resume the session token the last turn of this key left, or null
 * @param promptInArgs true when the command carries the prompt as an argument, so a terminal does
 *     not type it
 */
public record AgentRun(
    Message<?> message,
    AgentPreset preset,
    ProcessExecutionSpec spec,
    String prompt,
    AgentPreset.@Nullable Interactive talk,
    AgentRequests.@Nullable Settings approvals,
    long timeoutSeconds,
    AgentConversationHandle handle,
    PluginLogSink log,
    @Nullable InputIdentity who,
    boolean chat,
    Duration idleTimeout,
    Runnable heartbeat,
    @Nullable String resume,
    boolean promptInArgs) {

  /** Creates a run whose prompt is not an argument of the command. */
  @SuppressWarnings("PMD.ExcessiveParameterList") // the record's own components, minus one
  public AgentRun(
      final Message<?> message,
      final AgentPreset preset,
      final ProcessExecutionSpec spec,
      final String prompt,
      final AgentPreset.@Nullable Interactive talk,
      final AgentRequests.@Nullable Settings approvals,
      final long timeoutSeconds,
      final AgentConversationHandle handle,
      final PluginLogSink log,
      final @Nullable InputIdentity who,
      final boolean chat,
      final Duration idleTimeout,
      final Runnable heartbeat,
      final @Nullable String resume) {
    this(
        message,
        preset,
        spec,
        prompt,
        talk,
        approvals,
        timeoutSeconds,
        handle,
        log,
        who,
        chat,
        idleTimeout,
        heartbeat,
        resume,
        false);
  }
}
