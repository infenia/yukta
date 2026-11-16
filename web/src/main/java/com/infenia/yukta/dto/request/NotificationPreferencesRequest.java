// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.dto.request;

import com.infenia.yukta.service.notify.NotificationPreferences;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;
import java.util.Set;

/**
 * What a person wants to be told. The category {@code input} can never be OFF.
 *
 * @param levels the level per category: OFF, INBOX or DESKTOP
 * @param mutedSessions sessions whose notifications are not raised for this person
 */
@Schema(description = "Notification preferences of the caller")
public record NotificationPreferencesRequest(
    Map<String, NotificationPreferences.Level> levels, Set<String> mutedSessions) {

  /** Copies the collections; a missing one is empty. */
  public NotificationPreferencesRequest {
    levels = levels == null ? Map.of() : Map.copyOf(levels);
    mutedSessions = mutedSessions == null ? Set.of() : Set.copyOf(mutedSessions);
  }
}
