// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.event;

import com.infenia.yukta.model.execution.ControlState;

/**
 * Event representing a change of the operator control state of an execution.
 *
 * @param executionId the execution identifier
 * @param state the new control state
 */
public record ControlStateEvent(String executionId, ControlState state) implements TrackerEvent {}
