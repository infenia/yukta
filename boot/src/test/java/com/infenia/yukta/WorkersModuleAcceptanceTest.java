// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.service.worker.WorkerRegistry;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.aot.DisabledInAotMode;

/**
 * The real application with the workers module on the classpath but no worker properties: it must
 * start, and take no workers. Runs only in a build made with {@code -Pyukta.workers=true}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@DisabledInAotMode
@EnabledIfSystemProperty(named = "yukta.build.workers", matches = "true")
@NoArgsConstructor
class WorkersModuleAcceptanceTest {

  @Autowired private ApplicationContext context;
  @Autowired private WorkerRegistry registry;

  @Test
  void withoutWorkerProperties_theApplicationStartsWithoutWorkerBeans() {
    assertThat(registry.list()).isEmpty();
    assertThat(context.containsBean("workerController")).isFalse();
    assertThat(context.containsBean("nodeRunner")).isFalse();
    assertThat(context.containsBean("workerAgent")).isFalse();
  }
}
