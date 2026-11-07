// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor.composite;

import com.infenia.yukta.plugin.control.ControlPlanePlugin;
import com.infenia.yukta.plugin.control.ExecutionSupervisor;
import com.infenia.yukta.plugin.control.Supervisor;
import com.infenia.yukta.plugin.control.SupervisorCategory;
import com.infenia.yukta.plugin.control.SupervisorDescriptor;
import com.infenia.yukta.plugin.control.SupervisorFactory;
import com.infenia.yukta.plugin.core.ConfigField;
import com.infenia.yukta.plugin.core.ConfigField.Type;
import com.infenia.yukta.plugin.core.UiDesign;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
@SuppressWarnings("PMD.AvoidInstantiatingObjectsInLoops")
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
  public ExecutionSupervisor create(final Supervisor profile) {
    final CompositeSettings settings = CompositeSettings.read(profile);
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
      final SupervisorResolver resolver, final Supervisor node, final Set<String> path) {
    if (!path.add(node.id())) {
      throw new IllegalArgumentException("Composite profiles form a cycle at '" + node.id() + "'");
    }
    if (path.size() > CompositeSettings.MAX_DEPTH) {
      throw new IllegalArgumentException(
          "Composites nest at most " + CompositeSettings.MAX_DEPTH + " deep");
    }
    for (final String childId : CompositeSettings.read(node).children()) {
      final Supervisor child =
          resolver
              .profile(childId)
              .orElseThrow(() -> new IllegalArgumentException("Unknown child profile: " + childId));
      if (PROFILE_TYPE.equals(child.type())) {
        checkTree(resolver, child, path);
      }
    }
    path.remove(node.id());
  }

  @Override
  public SupervisorDescriptor describe() {
    return new SupervisorDescriptor(
        "Composite",
        "Runs other supervisors as one: one id, one scope and one set of metrics.",
        "Use it to chain control planes, take the first match, or combine supervisors by quorum.",
        SupervisorCategory.COMPOSITION,
        Optional.of(new UiDesign("amber", "account_tree", "Composite", 140, 80)),
        Map.of("mode", "PARALLEL", "children", List.of("rules", "model")));
  }

  @Override
  public List<ConfigField> getConfigSchema() {
    return List.of(
        ConfigField.choice(
            "mode",
            true,
            null,
            "How the children are combined",
            "CHAIN",
            "FIRST_MATCH",
            "PARALLEL",
            "QUORUM"),
        ConfigField.required(
            "children", Type.LIST, "The ids of the child supervisors, in order; at most 10"),
        ConfigField.choice(
            "review",
            false,
            "ALL",
            "How the reviews of the children add up",
            "ALL",
            "ANY",
            "MAJORITY"),
        ConfigField.optional(
            "quorum", Type.NUMBER, null, "For QUORUM: how many must agree; default a majority"),
        ConfigField.optional(
            "window",
            Type.NUMBER,
            500,
            "For QUORUM: milliseconds in which equal commands are collected"));
  }
}
