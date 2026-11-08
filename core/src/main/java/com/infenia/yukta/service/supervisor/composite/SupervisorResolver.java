// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor.composite;

import com.infenia.yukta.plugin.control.ExecutionSupervisor;
import com.infenia.yukta.plugin.control.Supervisor;
import com.infenia.yukta.plugin.control.SupervisorFactory;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Turns the id of a stored supervisor into a supervisor, for composites. It holds a copy of the
 * supervisors that {@code SupervisorService} refreshes before it validates or starts anything, so a
 * lookup never waits for the store.
 */
@Component
public class SupervisorResolver {

  /** The factories, looked up late: the service that owns them needs this resolver. */
  private final ObjectProvider<SupervisorFactory> factories;

  /** The supervisors by id. */
  private final Map<String, Supervisor> supervisors = new ConcurrentHashMap<>();

  /**
   * Creates the resolver.
   *
   * @param factories the supervisor factories
   */
  public SupervisorResolver(final ObjectProvider<SupervisorFactory> factories) {
    this.factories = factories;
  }

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
   * Puts a supervisor in this copy before it is stored, so a check sees the tree as it would be
   * after the save. {@link #refresh} puts the stored supervisors back.
   *
   * @param supervisor the supervisor about to be saved
   */
  public void tentatively(final Supervisor supervisor) {
    supervisors.put(supervisor.id(), supervisor);
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

  /**
   * Makes the supervisor of a stored supervisor.
   *
   * @param id the supervisor id
   * @return the supervisor; its id is the supervisor id until a attachment replaces it
   * @throws IllegalArgumentException if the supervisor or its type is unknown, or its settings
   *     invalid
   */
  public ExecutionSupervisor resolve(final String id) {
    final Supervisor supervisor =
        supervisor(id)
            .orElseThrow(() -> new IllegalArgumentException("Unknown child supervisor: " + id));
    return factories.stream()
        .filter(factory -> factory.type().equals(supervisor.type()))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("Child '" + id + "' has an unknown type"))
        .create(supervisor);
  }

  /**
   * The composites that list a supervisor as a child, directly or through other composites.
   *
   * @param id the supervisor id
   * @return the ids of those composite supervisors
   */
  public List<String> compositesUsing(final String id) {
    return supervisors.values().stream()
        .filter(supervisor -> CompositeSupervisorFactory.TYPE_NAME.equals(supervisor.type()))
        .filter(supervisor -> uses(supervisor, id, 0))
        .map(Supervisor::id)
        .sorted()
        .toList();
  }

  private boolean uses(final Supervisor composite, final String id, final int depth) {
    if (depth > CompositeSettings.MAX_DEPTH) {
      return false;
    }
    final Object children = composite.settings().get("children");
    return children instanceof List<?> list
        && list.stream()
            .anyMatch(
                child ->
                    id.equals(child)
                        || child instanceof String childId
                            && supervisor(childId)
                                .filter(p -> CompositeSupervisorFactory.TYPE_NAME.equals(p.type()))
                                .map(p -> uses(p, id, depth + 1))
                                .orElse(false));
  }
}
