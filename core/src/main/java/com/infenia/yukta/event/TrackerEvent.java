// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.event;

/** An event applied to the execution tracker, in the order in which it was emitted. */
public sealed interface TrackerEvent
    permits TaskStatusEvent, WorkflowStatusEvent, WorkflowLogEvent {}
