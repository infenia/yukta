// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.activity;

import java.time.Instant;

/**
 * One event of the activity feed as kept for the history: the fields it is searched by and the
 * event envelope as JSON.
 *
 * @param seq the store's number for the entry, rising with time; 0 before it is stored
 * @param at when the event happened
 * @param type the kind of event, e.g. {@code WorkflowStatusChanged}
 * @param severity {@code INFO}, {@code WARN} or {@code ERROR}
 * @param executionId the run it belongs to
 * @param json the envelope the live stream sends, as JSON
 */
public record ActivityEntry(
    long seq, Instant at, String type, String severity, String executionId, String json) {}
