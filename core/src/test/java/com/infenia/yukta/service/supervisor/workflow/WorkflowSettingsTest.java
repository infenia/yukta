// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.infenia.yukta.plugin.control.SupervisorProfile;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

@SuppressWarnings({"PMD.AvoidDuplicateLiterals", "PMD.UseConcurrentHashMap"})
class WorkflowSettingsTest {

  private static Map<String, Object> base() {
    final Map<String, Object> m = new HashMap<>();
    m.put("sessionId", "sys");
    m.put("workflowId", "wf");
    m.put("events", List.of("InputRequested"));
    return m;
  }

  private static WorkflowSettings read(final Map<String, Object> m) {
    return WorkflowSettings.read(new SupervisorProfile("p", "workflow", m, false), 4);
  }

  @Test
  void defaultsAndExplicitValues() {
    final WorkflowSettings d = read(base());
    assertThat(d.maxConcurrent()).isEqualTo(2);
    assertThat(d.timeout()).isEqualTo(Duration.ofSeconds(60));
    assertThat(d.events()).containsExactly("InputRequested");
    final Map<String, Object> m = base();
    m.put("maxConcurrent", 3);
    m.put("timeout", 5);
    assertThat(read(m).maxConcurrent()).isEqualTo(3);
    assertThat(read(m).timeout()).isEqualTo(Duration.ofSeconds(5));
  }

  @Test
  void badValuesAreRefused() {
    final Map<String, Object> m = base();
    m.put("maxConcurrent", 5);
    assertThatThrownBy(() -> read(m)).hasMessageContaining("reserved slots (4)");
    m.put("maxConcurrent", "x");
    assertThatThrownBy(() -> read(m)).hasMessageContaining("number");
    m.put("maxConcurrent", 1);
    m.put("timeout", 601);
    assertThatThrownBy(() -> read(m)).hasMessageContaining("timeout");
    m.put("maxConcurrent", 0);
    assertThatThrownBy(() -> read(m)).hasMessageContaining("maxConcurrent");
    m.put("maxConcurrent", 1);
    m.put("timeout", 0);
    assertThatThrownBy(() -> read(m)).hasMessageContaining("timeout");
    final Map<String, Object> notAList = base();
    notAList.put("events", "InputRequested");
    assertThatThrownBy(() -> read(notAList)).hasMessageContaining("events");
    final Map<String, Object> noWorkflow = base();
    noWorkflow.put("workflowId", 7);
    assertThatThrownBy(() -> read(noWorkflow)).hasMessageContaining("workflowId");
    final Map<String, Object> noSession = base();
    noSession.remove("sessionId");
    assertThatThrownBy(() -> read(noSession)).hasMessageContaining("sessionId");
    final Map<String, Object> noEvents = base();
    noEvents.put("events", List.of());
    assertThatThrownBy(() -> read(noEvents)).hasMessageContaining("events");
    noEvents.put("events", List.of(1));
    assertThatThrownBy(() -> read(noEvents)).hasMessageContaining("events");
  }
}
