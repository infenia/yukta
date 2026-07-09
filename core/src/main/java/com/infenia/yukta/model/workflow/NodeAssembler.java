// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.model.workflow;

import com.infenia.yukta.service.orchestrator.assembly.AssemblyContext;

/**
 * A functional interface for reifying a single node's reactive stream during workflow
 * instantiation.
 *
 * <p>Takes a single {@link AssemblyContext} parameter bundling all state, execution context, and
 * control mechanisms needed to assemble the node's stream.
 */
@FunctionalInterface
public interface NodeAssembler {
  /**
   * Assembles the reactive stream for a node.
   *
   * @param context the assembly context containing execution state, control handles, and
   *     collections
   */
  void assemble(AssemblyContext context);
}
