// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.model.workflow;

import jakarta.annotation.Nullable;

/**
 * An internal representation of a directed edge in a workflow DAG.
 *
 * <p>This is an internal model type with no Bean Validation annotations. It is produced from {@code
 * WorkflowDefinition.Edge} at preparation time and carried through the execution pipeline
 * independently of the REST layer.
 *
 * @param source the source node ID
 * @param target the target node ID
 * @param sourcePort the optional output port on the source node; null means no port filtering
 */
public record WorkflowEdge(String source, String target, @Nullable String sourcePort) {}
