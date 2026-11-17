// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.startup;

import com.infenia.yukta.plugin.store.NodeCheckpointStore;
import com.infenia.yukta.service.agent.AgentConversationStore;
import com.infenia.yukta.service.input.InputRequestStore;
import com.infenia.yukta.service.leader.LeaderElection;
import com.infenia.yukta.service.log.ExecutionLogStore;
import com.infenia.yukta.service.notify.NotificationStore;
import com.infenia.yukta.service.sessionstore.SessionConfigStore;
import com.infenia.yukta.service.supervisor.SupervisorStore;
import com.infenia.yukta.service.workflow.store.WorkflowDefinitionStore;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;

/**
 * Logs one line at start-up naming the store that keeps each kind of data, so an operator can tell
 * from the running server which of them are durable: each kind has its own switch, and nothing else
 * says when they disagree.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StoreSummary {

  /** The kinds of data, in the order logged. */
  private static final Map<String, Class<?>> KINDS = kinds();

  /** Where the stores are looked up. */
  private final ListableBeanFactory beans;

  private static Map<String, Class<?>> kinds() {
    final Map<String, Class<?>> kinds = new LinkedHashMap<>();
    kinds.put("sessions", SessionConfigStore.class);
    kinds.put("definitions", WorkflowDefinitionStore.class);
    kinds.put("executionLog", ExecutionLogStore.class);
    kinds.put("checkpoints", NodeCheckpointStore.class);
    kinds.put("inputs", InputRequestStore.class);
    kinds.put("notifications", NotificationStore.class);
    kinds.put("supervisors", SupervisorStore.class);
    kinds.put("agents", AgentConversationStore.class);
    kinds.put("leader", LeaderElection.class);
    return kinds;
  }

  /** Logs the stores in use. */
  @EventListener(ApplicationReadyEvent.class)
  public void onReady() {
    var line = log.atInfo();
    for (final Map.Entry<String, String> store : describe().entrySet()) {
      line = line.addKeyValue(store.getKey(), store.getValue());
    }
    line.log("Stores in use");
  }

  /**
   * The class of the store that keeps each kind of data, or {@code none}.
   *
   * @return kind to simple class name, in a fixed order
   */
  /* default */ Map<String, String> describe() {
    final Map<String, String> stores = new LinkedHashMap<>();
    KINDS.forEach((kind, type) ->
        stores.put(
            kind,
            beans.getBeanProvider(type).stream()
                .findFirst()
                .map(bean -> ClassUtils.getUserClass(bean).getSimpleName())
                .orElse("none")));
    return stores;
  }
}
