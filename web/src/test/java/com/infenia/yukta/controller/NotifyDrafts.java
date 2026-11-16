// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.controller;

import com.infenia.yukta.plugin.notify.Notification;
import com.infenia.yukta.service.notify.NotificationDraft;
import java.time.Duration;
import lombok.experimental.UtilityClass;

/** Drafts for the notification controller tests. */
@UtilityClass
/* default */ class NotifyDrafts {

  /** The draft that raises an existing notification again, so it counts as updated. */
  /* default */ static NotificationDraft again(final Notification n) {
    return NotificationDraft.builder()
        .key(n.key())
        .kind(n.kind())
        .severity(n.severity())
        .category(n.category())
        .title(n.title() + "!")
        .sessionId(n.sessionId())
        .audience(n.audience())
        .cooldown(Duration.ZERO)
        .build();
  }
}
