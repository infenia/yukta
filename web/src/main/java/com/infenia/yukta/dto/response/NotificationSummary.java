// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.dto.response;

import com.infenia.yukta.plugin.notify.NotificationSeverity;
import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

/**
 * What the bell shows.
 *
 * @param actions active actions for the caller
 * @param unread info notifications the caller has not read or dismissed
 * @param highestSeverity the highest severity of those, or {@code null} when there are none
 */
@Schema(description = "What the notification bell shows")
public record NotificationSummary(
    long actions, long unread, @Nullable NotificationSeverity highestSeverity) {}
