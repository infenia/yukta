// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import lombok.NoArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * The supervisors attached at run time, by binding id. {@link SupervisorHost} runs them and {@link
 * ControlReviewer} asks them to review commands; this holder lets both see the same set without
 * depending on each other.
 */
@Component
@NoArgsConstructor
public class AttachedSupervisors {

  /** Attached supervisors by binding id. */
  private final Map<String, BoundSupervisor> attached = new ConcurrentHashMap<>();

  /**
   * Adds a supervisor, replacing one with the same binding id.
   *
   * @param supervisor the supervisor
   * @return the one it replaced, or null
   */
  /* default */ BoundSupervisor put(final BoundSupervisor supervisor) {
    return attached.put(supervisor.id(), supervisor);
  }

  /**
   * Removes a supervisor.
   *
   * @param bindingId the binding id
   * @return the supervisor, or null if none was attached
   */
  /* default */ BoundSupervisor remove(final String bindingId) {
    return attached.remove(bindingId);
  }

  /**
   * The attached supervisors.
   *
   * @return a snapshot stream
   */
  public Stream<BoundSupervisor> stream() {
    return attached.values().stream();
  }
}
