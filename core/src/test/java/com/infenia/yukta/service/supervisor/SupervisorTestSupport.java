// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor;

import com.infenia.yukta.plugin.control.ExecutionSupervisor;
import lombok.experimental.UtilityClass;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/** Builds the supervisor lookup the Spring context would provide. */
@UtilityClass
public class SupervisorTestSupport {

  /**
   * Lookup that finds exactly the given supervisors.
   *
   * @param supervisors the supervisors
   * @return the lookup
   */
  public static ObjectProvider<ExecutionSupervisor> providerOf(
      final ExecutionSupervisor... supervisors) {
    final StaticListableBeanFactory factory = new StaticListableBeanFactory();
    for (final ExecutionSupervisor supervisor : supervisors) {
      factory.addBean(supervisor.id(), supervisor);
    }
    return factory.getBeanProvider(ExecutionSupervisor.class);
  }
}
