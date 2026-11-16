// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Which notifications to mark read: the listed ids, or all up to a change sequence.
 *
 * @param ids the notification ids
 * @param all true to mark every notification the caller sees; absent means false
 * @param upToSeq with {@code all}, only those changed up to this sequence; {@code null} for every
 *     one
 */
@Schema(description = "Mark notifications read: by ids, or all up to a sequence")
public record NotificationReadRequest(
    @Nullable List<String> ids, @Nullable Boolean all, @Nullable Long upToSeq) {

  /** Keeps an immutable copy of the ids. */
  @SuppressWarnings("PMD.NullAssignment") // absent means none
  public NotificationReadRequest {
    ids = ids == null ? null : List.copyOf(ids);
  }
}
