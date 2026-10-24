// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.testsupport;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import lombok.NoArgsConstructor;

/** Keeps logging events in a queue that another thread can read while logging goes on. */
@NoArgsConstructor
@SuppressWarnings("PMD.CommentRequired")
final class EventCollector extends AppenderBase<ILoggingEvent> {

  /* default */ final Queue<ILoggingEvent> events = new ConcurrentLinkedQueue<>();

  @Override
  protected void append(final ILoggingEvent event) {
    events.add(event);
  }
}
