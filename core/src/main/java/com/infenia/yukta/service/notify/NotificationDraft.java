// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.notify;

import com.infenia.yukta.plugin.notify.NotificationKind;
import com.infenia.yukta.plugin.notify.NotificationSeverity;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Duration;
import java.util.List;
import lombok.Builder;
import org.jspecify.annotations.Nullable;

/**
 * What a source asks the service to raise.
 *
 * <p>Components: key (dedupe), kind, severity, category, title, body, session, workflow, execution,
 * node, input request, audience, and a cooldown (a repeat of the key inside it raises the count
 * instead of changing the item; zero for none).
 */
@Builder
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP",
    justification = "the compact constructor stores an unmodifiable copy")
public record NotificationDraft(
    String key,
    NotificationKind kind,
    NotificationSeverity severity,
    String category,
    String title,
    @Nullable String body,
    @Nullable String sessionId,
    @Nullable String workflowId,
    @Nullable String executionId,
    @Nullable String nodeId,
    @Nullable String inputRequestId,
    List<String> audience,
    Duration cooldown) {

  /** Keeps an immutable copy of the audience. */
  public NotificationDraft {
    audience = List.copyOf(audience);
  }
}
