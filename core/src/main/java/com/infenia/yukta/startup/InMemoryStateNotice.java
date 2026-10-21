// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.startup;

import com.infenia.yukta.service.session.store.InMemorySessionConfigStore;
import com.infenia.yukta.service.session.store.SessionConfigStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Says once at start-up that sessions, workflows and run history are kept in memory, so a user who
 * expected them to survive a restart finds out before losing them.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InMemoryStateNotice {

  /** Where sessions are kept. */
  private final SessionConfigStore sessions;

  /** Logs the notice when sessions are kept in memory. */
  @EventListener(ApplicationReadyEvent.class)
  public void onReady() {
    announce();
  }

  /* default */ boolean announce() {
    final boolean inMemory = sessions instanceof InMemorySessionConfigStore;
    if (inMemory) {
      log.atInfo().log(
          "Sessions, workflows and run history are kept in memory and are lost when the server"
              + " stops. To start with your workflows, set yukta.bootstrap.dir to a folder of"
              + " session files. See https://github.com/infenia/yukta/blob/main/LICENSING.md");
    }
    return inMemory;
  }
}
