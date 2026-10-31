// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.control;

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
}
