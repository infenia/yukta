// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.dto.response;

import com.infenia.yukta.plugin.notify.Notification;
import com.infenia.yukta.plugin.notify.NotificationKind;
import com.infenia.yukta.plugin.notify.NotificationSeverity;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * A notification as one person sees it. The title and body can come from a workflow or an AI agent:
 * show them as plain, untrusted text.
 *
 * @param id the id
 * @param key the dedupe key, such as {@code input:<requestId>}
 * @param kind info or action
 * @param severity how serious it is
 * @param category the category, such as {@code input} or {@code run.failed}
 * @param title one line
 * @param body plain text, or {@code null}
 * @param sessionId the session, or {@code null}
 * @param workflowId the workflow, or {@code null}
 * @param executionId the run, or {@code null}
 * @param nodeId the node, or {@code null}
 * @param inputRequestId the input request of an action, or {@code null}
 * @param state ACTIVE or RESOLVED
 * @param resolution how it ended, or {@code null} while active
 * @param count how many times the same key fired inside its cooldown
 * @param seq the change sequence at the last change
 * @param createdAt when it was raised
 * @param updatedAt when it last changed
 * @param read whether this caller read it
 * @param dismissed whether this caller dismissed it
 * @param input the input request of an action, so it can be answered without a second call; {@code
 *     null} for an info or when the request is gone
 */
@Schema(description = "A notification as one person sees it")
public record NotificationView(
    String id,
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
    Notification.State state,
    @Nullable String resolution,
    int count,
    long seq,
    Instant createdAt,
    Instant updatedAt,
    boolean read,
    boolean dismissed,
    @Nullable InputRequestView input) {

  /**
   * The view of a notification for a caller.
   *
   * @param n the notification
   * @param read whether the caller read it
   * @param dismissed whether the caller dismissed it
   * @param input the input request of an action, or {@code null}
   * @return the view
   */
  public static NotificationView from(
      final Notification n,
      final boolean read,
      final boolean dismissed,
      final @Nullable InputRequestView input) {
    return new NotificationView(
        n.id(),
        n.key(),
        n.kind(),
        n.severity(),
        n.category(),
        n.title(),
        n.body(),
        n.sessionId(),
        n.workflowId(),
        n.executionId(),
        n.nodeId(),
        n.inputRequestId(),
        n.state(),
        n.resolution(),
        n.count(),
        n.seq(),
        n.createdAt(),
        n.updatedAt(),
        read,
        dismissed,
        input);
  }
}
