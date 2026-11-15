// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.agentsession.adapter;

import com.infenia.yukta.plugin.agent.AgentCommand;
import com.infenia.yukta.plugin.agent.AgentConversationHandle;
import com.infenia.yukta.plugin.agent.AgentEventKind;
import com.infenia.yukta.plugin.agent.AgentState;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import reactor.core.publisher.Flux;

/** A conversation handle that writes down what it is given, as readable lines. */
public final class RecordingConversation implements AgentConversationHandle {

  private final List<String> lines = new CopyOnWriteArrayList<>();

  /**
   * Returns what was written so far.
   *
   * @return one line per call, in order
   */
  public List<String> calls() {
    return List.copyOf(lines);
  }

  @Override
  @SuppressWarnings("PMD.ShortMethodName")
  public String id() {
    return "c-test";
  }

  @Override
  public void emit(final AgentEventKind kind, final String itemId, final Map<String, Object> data) {
    lines.add("emit " + kind + " " + itemId + " " + data);
  }

  @Override
  public void state(final AgentState state, final String reason) {
    lines.add("state " + state + " " + reason);
  }

  @Override
  public void queued(final int count) {
    lines.add("queued " + count);
  }

  @Override
  public Flux<AgentCommand> commands() {
    return Flux.never();
  }

  @Override
  public void end(final String outcome, final String reason) {
    lines.add("end " + outcome + " " + reason);
  }
}
