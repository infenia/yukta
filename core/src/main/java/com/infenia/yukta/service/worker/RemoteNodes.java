// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.worker;

import com.infenia.yukta.model.workflow.NodePolicy;
import com.infenia.yukta.plugin.core.Plugin;
import com.infenia.yukta.plugin.type.ProcessorPlugin;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Decides, when a workflow is prepared, which nodes run on a worker: those whose config has {@code
 * engine.placement}. They get a {@link RemoteProcessor} in place of the local plugin.
 */
@Component
public class RemoteNodes {

  /**
   * Plugin types that keep state in this process, so they cannot run on another machine yet (their
   * state moves with the database backends of F7).
   */
  private static final Set<String> LOCAL_ONLY =
      Set.of("SESSION_MEMORY", "KV_STORE", "WAIT_FOR_SIGNAL", "SUB_WORKFLOW");

  /** Runs calls on workers, if the workers module is part of the application. */
  private final ObjectProvider<RemoteNodeExecutor> executor;

  /**
   * Creates the component.
   *
   * @param executor runs calls on workers, if the workers module is present
   */
  public RemoteNodes(final ObjectProvider<RemoteNodeExecutor> executor) {
    this.executor = executor;
  }

  /**
   * The plugin a node runs with.
   *
   * @param type the node's plugin type
   * @param config the node's configuration
   * @param local the plugin on this server
   * @return {@code local} for a node without placement, otherwise a stand-in that runs on a worker
   * @throws IllegalArgumentException if the node cannot be placed
   * @throws IllegalStateException if workers are not enabled
   */
  public Plugin place(final String type, final Map<String, Object> config, final Plugin local) {
    final NodePolicy.Placement placement = NodePolicy.placement(config);
    Plugin result = local;
    if (placement != null) {
      if (!(local instanceof final ProcessorPlugin processor)) {
        throw new IllegalArgumentException(
            "engine.placement is only for processor nodes, but " + type + " is not one");
      }
      if (LOCAL_ONLY.contains(type)) {
        throw new IllegalArgumentException(
            type + " keeps state on this server, so it cannot have engine.placement");
      }
      final RemoteNodeExecutor remote = executor.getIfAvailable();
      if (remote == null) {
        throw new IllegalStateException(
            "engine.placement needs the workers module (build with -Pyukta.workers=true)");
      }
      result = new RemoteProcessor(processor, remote, placement.pool());
    }
    return result;
  }
}
