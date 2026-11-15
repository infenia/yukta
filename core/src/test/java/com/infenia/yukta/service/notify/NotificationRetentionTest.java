// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.notify;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.service.input.InputTestSupport.MutableClock;
import com.infenia.yukta.service.redact.InputRedactor;
import java.time.Duration;
import java.util.List;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import reactor.core.scheduler.Schedulers;

@NoArgsConstructor
class NotificationRetentionTest {

  @Test
  void purgeRemovesWhatIsOlderThanTheRetention() {
    final MutableClock clock = new MutableClock();
    final InMemoryNotificationStore store = new InMemoryNotificationStore(10);
    final NotificationService service =
        new NotificationService(store, new InputRedactor(List.of()), clock);
    service.raise(NotifyTestSupport.info("old").build()).block();
    clock.advance(Duration.ofDays(8));
    service.raise(NotifyTestSupport.info("new").build()).block();
    final NotificationRetention retention =
        new NotificationRetention(
            store,
            () -> true,
            Duration.ofDays(7),
            Duration.ofHours(1),
            Schedulers.parallel(),
            clock);

    retention.purge().block();

    assertThat(store.find(NotificationQuery.ALL).count().block()).isEqualTo(1);
  }
}
