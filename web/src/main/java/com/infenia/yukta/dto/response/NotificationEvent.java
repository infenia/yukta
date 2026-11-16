// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * One event of the notification stream.
 *
 * @param type {@code created}, {@code updated}, {@code resolved}, {@code receipt} or {@code reset}
 * @param notification the notification, for created, updated and resolved
 * @param notificationId the notification a receipt is about
 * @param readAt when the caller read it, for a receipt
 * @param dismissedAt when the caller dismissed it, for a receipt
 */
@Schema(description = "One event of the notification stream")
public record NotificationEvent(
    String type,
    @Nullable NotificationView notification,
    @Nullable String notificationId,
    @Nullable Instant readAt,
    @Nullable Instant dismissedAt) {}
