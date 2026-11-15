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
 * @param key dedupe key
 * @param kind info or action
 * @param severity how serious it is
 * @param category the category
 * @param title one line
 * @param body plain text, or {@code null}
 * @param sessionId the session, or {@code null}
 * @param workflowId the workflow, or {@code null}
 * @param executionId the run, or {@code null}
 * @param nodeId the node, or {@code null}
 * @param inputRequestId the input request of an action, or {@code null}
 * @param audience who sees it
 * @param cooldown a repeat of the key inside this time raises the count instead of changing the
 *     item; zero for none
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
