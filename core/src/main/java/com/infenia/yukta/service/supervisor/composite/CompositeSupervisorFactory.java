// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor.composite;

import com.infenia.yukta.plugin.control.ControlPlanePlugin;
import com.infenia.yukta.plugin.control.ExecutionSupervisor;
import com.infenia.yukta.plugin.control.SupervisorFactory;
import com.infenia.yukta.plugin.control.SupervisorProfile;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Makes composite supervisors: other profiles run inside one supervisor with one id, one scope and
 * one set of metrics. Settings: {@code mode} ({@code CHAIN}, {@code FIRST_MATCH}, {@code PARALLEL},
 * {@code QUORUM}), {@code children} (profile ids), {@code review} ({@code ALL}, {@code ANY}, {@code
 * MAJORITY}), and for {@code QUORUM} {@code quorum} (default a majority) and {@code window} in
 * milliseconds (default 500).
 *
 * <p>A child's own scope is not checked: a profile has none, only its binding does, and the
 * children run inside the composite's binding.
 */
@Component
@RequiredArgsConstructor
public class CompositeSupervisorFactory implements SupervisorFactory {

  /** The supervisor type in a profile. */
  public static final String PROFILE_TYPE = "composite";

  /** Looked up late: the service that holds the factories needs the resolver. */
  private final ObjectProvider<SupervisorResolver> resolvers;

  @Override
  public String type() {
    return PROFILE_TYPE;
  }

  @Override
  public ExecutionSupervisor create(final SupervisorProfile profile) {
    final CompositeSettings settings = CompositeSettings.of(profile);
    final SupervisorResolver resolver = resolvers.getObject();
    checkTree(resolver, profile, new LinkedHashSet<>());
    final List<ExecutionSupervisor> children = new ArrayList<>();
    for (final String childId : settings.children()) {
      final ExecutionSupervisor child = resolver.resolve(childId);
      if (settings.mode().needsControlPlanes() && !(child instanceof ControlPlanePlugin)) {
        throw new IllegalArgumentException(
            "Mode "
                + settings.mode()
                + " needs control planes as children; '"
                + childId
                + "' is not one (use PARALLEL or QUORUM)");
      }
      children.add(child);
    }
    return new CompositeSupervisor(
        profile.id(), settings, settings.children(), children, Clock.systemUTC());
  }

  /** Looks for a cycle and for nesting that is too deep, before anything is built. */
  private static void checkTree(
      final SupervisorResolver resolver, final SupervisorProfile node, final Set<String> path) {
    if (!path.add(node.id())) {
      throw new IllegalArgumentException("Composite profiles form a cycle at '" + node.id() + "'");
    }
    if (path.size() > CompositeSettings.MAX_DEPTH) {
      throw new IllegalArgumentException(
          "Composites nest at most " + CompositeSettings.MAX_DEPTH + " deep");
    }
    for (final String childId : CompositeSettings.of(node).children()) {
      final SupervisorProfile child =
          resolver
              .profile(childId)
              .orElseThrow(() -> new IllegalArgumentException("Unknown child profile: " + childId));
      if (PROFILE_TYPE.equals(child.type())) {
        checkTree(resolver, child, path);
      }
    }
    path.remove(node.id());
  }
}
