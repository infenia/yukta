// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.startup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;

import com.infenia.yukta.service.session.store.InMemorySessionConfigStore;
import com.infenia.yukta.service.session.store.SessionConfigStore;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

/** Tests for {@link InMemoryStateNotice}. */
@NoArgsConstructor
class InMemoryStateNoticeTest {

  @Test
  void announcesOnlyWhenSessionsAreInMemory() {
    assertThat(new InMemoryStateNotice(mock(InMemorySessionConfigStore.class)).announce()).isTrue();
    assertThat(new InMemoryStateNotice(mock(SessionConfigStore.class)).announce()).isFalse();
  }

  @Test
  void onReadyDoesNotFail() {
    assertThatCode(() -> new InMemoryStateNotice(mock(SessionConfigStore.class)).onReady())
        .doesNotThrowAnyException();
  }
}
