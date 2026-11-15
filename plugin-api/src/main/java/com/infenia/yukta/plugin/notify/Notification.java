// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.notify;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Something Yukta tells a person (an {@link NotificationKind#INFO} alert) or needs from a person
 * (an {@link NotificationKind#ACTION}, whose input request stays the single truth).
 *
 * @param id the id; assigned by the store
 * @param key dedupe key: {@code input:<requestId>}, {@code run.failed:<executionId>}, or the
 *     author's
 * @param kind info or action
 * @param severity how serious it is
 * @param category {@code input}, {@code run.failed}, {@code run.finished}, {@code run.held}, {@code
 *     delivery.failed} or {@code workflow}
 * @param title one line, redacted, at most {@link #TITLE_MAX} characters
 * @param body plain text, redacted, at most {@link #BODY_MAX} characters, or {@code null}
 * @param sessionId the session, or {@code null}
 * @param workflowId the workflow, or {@code null}
 * @param executionId the run, or {@code null}
 * @param nodeId the node, or {@code null}
 * @param inputRequestId the input request of an action, or {@code null}
 * @param audience who sees it: {@code role:VIEWER|OPERATOR|ADMIN} or {@code key:<name>}
 * @param state active or resolved
 * @param resolution how it ended, such as {@code answered by key:arun: approve}; {@code null} while
 *     active
 * @param count how many times the same key fired inside its cooldown
 * @param seq the store's change sequence at the last change; assigned by the store
 * @param createdAt when it was raised
 * @param updatedAt when it last changed
 * @since 0.1.0-alpha.5
 */
public record Notification(
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
    List<String> audience,
    State state,
    @Nullable String resolution,
    int count,
    long seq,
    Instant createdAt,
    Instant updatedAt) {

  /** Longest title, in characters. */
  public static final int TITLE_MAX = 200;

  /** Longest body, in characters (4 KB of ASCII). */
  public static final int BODY_MAX = 4096;

  /** Where a notification is in its life. */
  public enum State {

    /** Still shown as current. */
    ACTIVE,

    /** Over; terminal, it never becomes active again. */
    RESOLVED
  }

  /** Checks the required fields and the length limits, and keeps an immutable audience. */
  public Notification {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(key, "key");
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(severity, "severity");
    Objects.requireNonNull(category, "category");
    Objects.requireNonNull(title, "title");
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(createdAt, "createdAt");
    Objects.requireNonNull(updatedAt, "updatedAt");
    if (title.length() > TITLE_MAX) {
      throw new IllegalArgumentException("title is longer than " + TITLE_MAX + " characters");
    }
    if (body != null && body.length() > BODY_MAX) {
      throw new IllegalArgumentException("body is longer than " + BODY_MAX + " characters");
    }
    audience = List.copyOf(audience);
  }

  /**
   * A copy that ended.
   *
   * @param how how it ended
   * @param newSeq the change sequence of the change
   * @param at when
   * @return the resolved notification
   */
  public Notification resolved(final String how, final long newSeq, final Instant at) {
    return new Notification(
        id,
        key,
        kind,
        severity,
        category,
        title,
        body,
        sessionId,
        workflowId,
        executionId,
        nodeId,
        inputRequestId,
        audience,
        State.RESOLVED,
        how,
        count,
        newSeq,
        createdAt,
        at);
  }

  /**
   * A copy with the id and change sequence the store assigned.
   *
   * @param newId the id
   * @param newSeq the change sequence
   * @return the stored notification
   */
  public Notification stored(final String newId, final long newSeq) {
    return new Notification(
        newId,
        key,
        kind,
        severity,
        category,
        title,
        body,
        sessionId,
        workflowId,
        executionId,
        nodeId,
        inputRequestId,
        audience,
        state,
        resolution,
        count,
        newSeq,
        createdAt,
        updatedAt);
  }
}
