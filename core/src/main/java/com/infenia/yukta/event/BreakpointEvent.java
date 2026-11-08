// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.event;

import com.infenia.yukta.model.execution.TaskProgress;
import org.jspecify.annotations.Nullable;

/**
 * Event representing a breakpoint set on, or cleared from, a node of an execution.
 *
 * @param executionId the execution identifier
 * @param nodeId the node identifier
 * @param breakpoint the breakpoint, or {@code null} to clear it
 */
public record BreakpointEvent(
    String executionId, String nodeId, TaskProgress.@Nullable Breakpoint breakpoint)
    implements TrackerEvent {}
