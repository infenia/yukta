// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.control;

import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Makes supervisors of one kind from profiles, so they can be attached at run time. Register an
 * implementation as a Spring bean next to the supervisor it makes.
 */
public interface SupervisorFactory {

  /**
   * The kind of supervisor this factory makes, as written in {@link SupervisorProfile#type()}.
   *
   * @return the type name
   */
  String type();

  /**
   * Makes a supervisor from a profile. The supervisor's own {@link ExecutionSupervisor#id()} and
   * {@link ExecutionSupervisor#scope()} are replaced by the binding's.
   *
   * @param profile the profile
   * @return the supervisor
   * @throws IllegalArgumentException if the settings are invalid; the message is shown to the
   *     caller, so it must not repeat a secret
   */
  ExecutionSupervisor create(SupervisorProfile profile);

  /**
   * Checks a profile without keeping anything. The default makes a supervisor and drops it, which
   * is right while {@link #create} only reads settings; override it when {@code create} opens a
   * client or a connection, so that saving a profile does not leak one.
   *
   * @param profile the profile
   * @throws IllegalArgumentException if the settings are invalid
   */
  default void validate(final SupervisorProfile profile) {
    create(profile);
  }

  /**
   * The command types a supervisor made by this factory may send when its profile does not say.
   * {@code null} means every command, which is what supervisors have always been allowed; a factory
   * of a kind that should be granted commands one by one returns an empty set.
   *
   * <p>Profile settings read by {@link SupervisorCapabilities}: {@code allowedCommands} (a list of
   * command names such as {@code PauseWorkflowCommand}), {@code canAnswer} (whether the supervisor
   * may answer input requests, default false) and {@code maxRisk} (the highest risk it may answer,
   * {@code LOW}, {@code MEDIUM} or {@code HIGH}; default {@code LOW}).
   *
   * @return the command names, or {@code null} for no restriction
   */
  @SuppressWarnings("PMD.ReturnEmptyCollectionRatherThanNull") // null means no restriction
  default @Nullable Set<String> defaultAllowedCommands() {
    return null;
  }
}
