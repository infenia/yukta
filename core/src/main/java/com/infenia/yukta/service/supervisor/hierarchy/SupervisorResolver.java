// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor.hierarchy;

import com.infenia.yukta.plugin.control.Supervisor;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * A copy of the stored supervisors that {@code SupervisorService} refreshes before it checks or
 * starts anything, so a lookup of a supervisor (for example a parent) never waits for the store.
 */
@Component
public class SupervisorResolver {

  /** The supervisors by id. */
  private final Map<String, Supervisor> supervisors = new ConcurrentHashMap<>();

  /**
   * Replaces the copy of the supervisors.
   *
   * @param all every stored supervisor
   */
  public void refresh(final Collection<Supervisor> all) {
    final Map<String, Supervisor> next =
        all.stream().collect(Collectors.toMap(Supervisor::id, Function.identity()));
    supervisors.keySet().retainAll(next.keySet());
    supervisors.putAll(next);
  }

  /**
   * Looks a supervisor up.
   *
   * @param id the supervisor id
   * @return the supervisor, if stored
   */
  public Optional<Supervisor> supervisor(final String id) {
    return Optional.ofNullable(supervisors.get(id));
  }
}
