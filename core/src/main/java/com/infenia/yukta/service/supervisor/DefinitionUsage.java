// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor;

import java.util.List;
import reactor.core.publisher.Mono;

/** Tells which sessions and workflows declare a supervisor, so one that is in use is kept. */
@FunctionalInterface
public interface DefinitionUsage {

  /**
   * Who declares a supervisor.
   *
   * @param supervisorId the supervisor
   * @return the sessions and workflows that declare it, such as {@code workflow s1/build}
   */
  Mono<List<String>> declaredBy(String supervisorId);
}
