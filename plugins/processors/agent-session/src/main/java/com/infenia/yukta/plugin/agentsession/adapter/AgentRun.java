// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.agentsession.adapter;

import com.infenia.yukta.message.Message;
import com.infenia.yukta.plugin.agent.AgentConversationHandle;
import com.infenia.yukta.plugin.agentsession.AgentPreset;
import com.infenia.yukta.plugin.core.PluginLogSink;
import com.infenia.yukta.plugin.input.InputIdentity;
import com.infenia.yukta.plugin.process.ProcessExecutionSpec;
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
 * @param conversation where the run records what happens; a handle that drops everything when
 *     conversations are off
 * @param log the run log of the node
 * @param who the run and node, or null when the run has no identity (a test, a direct call)
 */
public record AgentRun(
    Message<?> message,
    AgentPreset preset,
    ProcessExecutionSpec spec,
    String prompt,
    AgentPreset.@Nullable Interactive talk,
    AgentRequests.@Nullable Settings approvals,
    long timeoutSeconds,
    AgentConversationHandle conversation,
    PluginLogSink log,
    @Nullable InputIdentity who) {}
