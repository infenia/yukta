// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor.composite;

import com.infenia.yukta.plugin.control.ExecutionSupervisor;
import com.infenia.yukta.plugin.control.SupervisorFactory;
import com.infenia.yukta.plugin.control.SupervisorProfile;
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
 * Turns the id of a stored profile into a supervisor, for composites. It holds a copy of the
 * profiles that {@code SupervisorService} refreshes before it validates or starts anything, so a
 * lookup never waits for the store.
 */
@Component
public class SupervisorResolver {

  /** The factories, looked up late: the service that owns them needs this resolver. */
  private final ObjectProvider<SupervisorFactory> factories;

  /** The profiles by id. */
  private final Map<String, SupervisorProfile> profiles = new ConcurrentHashMap<>();

  /**
   * Creates the resolver.
   *
   * @param factories the supervisor factories
   */
  public SupervisorResolver(final ObjectProvider<SupervisorFactory> factories) {
    this.factories = factories;
  }

  /**
   * Replaces the copy of the profiles.
   *
   * @param all every stored profile
   */
  public void refresh(final Collection<SupervisorProfile> all) {
    final Map<String, SupervisorProfile> next =
        all.stream().collect(Collectors.toMap(SupervisorProfile::id, Function.identity()));
    profiles.keySet().retainAll(next.keySet());
    profiles.putAll(next);
  }

  /**
   * Looks a profile up.
   *
   * @param id the profile id
   * @return the profile, if stored
   */
  public Optional<SupervisorProfile> profile(final String id) {
    return Optional.ofNullable(profiles.get(id));
  }

  /**
   * Makes the supervisor of a stored profile.
   *
   * @param id the profile id
   * @return the supervisor; its id is the profile id until a binding replaces it
   * @throws IllegalArgumentException if the profile or its type is unknown, or its settings invalid
   */
  public ExecutionSupervisor resolve(final String id) {
    final SupervisorProfile profile =
        profile(id).orElseThrow(() -> new IllegalArgumentException("Unknown child profile: " + id));
    return factories.stream()
        .filter(factory -> factory.type().equals(profile.type()))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("Child '" + id + "' has an unknown type"))
        .create(profile);
  }

  /**
   * The composites that list a profile as a child, directly or through other composites.
   *
   * @param id the profile id
   * @return the ids of those composite profiles
   */
  public List<String> compositesUsing(final String id) {
    return profiles.values().stream()
        .filter(profile -> CompositeSupervisorFactory.PROFILE_TYPE.equals(profile.type()))
        .filter(profile -> uses(profile, id, 0))
        .map(SupervisorProfile::id)
        .sorted()
        .toList();
  }

  private boolean uses(final SupervisorProfile composite, final String id, final int depth) {
    if (depth > CompositeSettings.MAX_DEPTH) {
      return false;
    }
    final Object children = composite.settings().get("children");
    if (!(children instanceof List<?> list)) {
      return false;
    }
    return list.stream()
        .anyMatch(
            child ->
                id.equals(child)
                    || child instanceof String childId
                        && profile(childId)
                            .filter(p -> CompositeSupervisorFactory.PROFILE_TYPE.equals(p.type()))
                            .map(p -> uses(p, id, depth + 1))
                            .orElse(false));
  }
}
