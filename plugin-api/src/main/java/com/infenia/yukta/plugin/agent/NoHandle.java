// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.agent;

import java.util.Map;
import org.jspecify.annotations.Nullable;
import reactor.core.publisher.Flux;

/** The handle that drops everything; see {@link AgentConversationHandle#none()}. */
final class NoHandle implements AgentConversationHandle {

  /** The only instance. */
  /* default */ static final NoHandle INSTANCE = new NoHandle();

  private NoHandle() {}

  @Override
  @SuppressWarnings("PMD.ShortMethodName")
  public String id() {
    return "none";
  }

  @Override
  public void emit(
      final AgentEventKind kind, final @Nullable String itemId, final Map<String, Object> data) {
    // dropped: nobody records this conversation
  }

  @Override
  public void state(final AgentState state, final @Nullable String reason) {
    // dropped
  }

  @Override
  public void queued(final int count) {
    // dropped
  }

  @Override
  public Flux<AgentCommand> commands() {
    return Flux.never();
  }

  @Override
  public void end(final String outcome, final @Nullable String reason) {
    AgentOutcome.require(outcome);
  }
}
