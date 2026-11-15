// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.notify;

import com.infenia.yukta.plugin.notify.NotificationKind;
import com.infenia.yukta.plugin.notify.NotificationSeverity;
import java.time.Duration;
import java.util.List;
import lombok.experimental.UtilityClass;

/** Drafts for the notification tests. */
@UtilityClass
/* default */ class NotifyTestSupport {

  /** An action for operators. */
  /* default */ static NotificationDraft.NotificationDraftBuilder action(final String key) {
    return NotificationDraft.builder()
        .key(key)
        .kind(NotificationKind.ACTION)
        .severity(NotificationSeverity.WARN)
        .category("input")
        .title("Deploy?")
        .inputRequestId("r1")
        .audience(List.of("role:OPERATOR"))
        .cooldown(Duration.ZERO);
  }

  /** An info notification for operators. */
  /* default */ static NotificationDraft.NotificationDraftBuilder info(final String key) {
    return NotificationDraft.builder()
        .key(key)
        .kind(NotificationKind.INFO)
        .severity(NotificationSeverity.ERROR)
        .category("run.failed")
        .title("Run failed")
        .audience(List.of("role:OPERATOR"))
        .cooldown(Duration.ZERO);
  }
}
