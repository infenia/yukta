// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.control;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * A configured supervisor: which kind of supervisor, how it is set up, and whether its commands are
 * only recorded. A {@link SupervisorAttachment} attaches it to a scope.
 *
 * @param id the supervisor id, unique
 * @param type the supervisor kind, matching a {@link SupervisorFactory#type()}
 * @param settings the settings the factory reads; may hold secrets, which APIs must not echo
 * @param dryRun whether the commands of the supervisor are only recorded
 * @param description what it is for, or {@code null}
 * @param parents the supervisors it reports to, in the order they are asked; empty for none
 * @param delegation how each kind of delegation is asked of the parents; a missing kind is asked in
 *     order with the default timeout
 */
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP",
    justification = "the compact constructor stores an unmodifiable copy")
public record Supervisor(
    String id,
    String type,
    Map<String, Object> settings,
    boolean dryRun,
    @Nullable String description,
    List<String> parents,
    Map<DelegationKind, Delegation> delegation) {

  /**
   * A supervisor without a description.
   *
   * @param id the supervisor id
   * @param type the supervisor kind
   * @param settings the settings
   * @param dryRun whether commands are only recorded
   */
  public Supervisor(
      final String id,
      final String type,
      final Map<String, Object> settings,
      final boolean dryRun) {
    this(id, type, settings, dryRun, null, List.of(), Map.of());
  }

  /**
   * A supervisor with no parents.
   *
   * @param id the id
   * @param type the type
   * @param settings the settings
   * @param dryRun whether its commands are only recorded
   * @param description what it is for, or {@code null}
   */
  public Supervisor(
      final String id,
      final String type,
      final Map<String, Object> settings,
      final boolean dryRun,
      @Nullable final String description) {
    this(id, type, settings, dryRun, description, List.of(), Map.of());
  }

  /** Rejects missing ids and keeps an immutable copy of the settings. */
  public Supervisor {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(type, "type");
    parents = parents == null ? List.of() : List.copyOf(parents);
    delegation = delegation == null ? Map.of() : Map.copyOf(delegation);
    // Map.copyOf rejects null values, and a setting may be null
    settings =
        settings == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(settings));
  }

  /**
   * How a kind of delegation is asked: the configured way, else ordered with the default timeout.
   *
   * @param kind the kind
   * @return the mode and timeout
   */
  public Delegation delegationFor(final DelegationKind kind) {
    return delegation.getOrDefault(kind, Delegation.DEFAULT);
  }
}
