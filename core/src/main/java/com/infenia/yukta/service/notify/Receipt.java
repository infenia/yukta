// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.notify;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * What one person did with one notification. No receipt means unread and not dismissed.
 *
 * @param notificationId the notification
 * @param recipient the key name, or {@link Recipients#LOCAL}
 * @param readAt when it was read, or {@code null}
 * @param dismissedAt when it was dismissed, or {@code null}
 */
public record Receipt(
    String notificationId,
    String recipient,
    @Nullable Instant readAt,
    @Nullable Instant dismissedAt) {}
