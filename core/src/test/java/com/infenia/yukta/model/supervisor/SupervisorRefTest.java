// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.model.supervisor;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.model.session.SessionConfigData;
import com.infenia.yukta.model.session.SessionConfigResponse;
import com.infenia.yukta.model.workflow.WorkflowDefinition;
import com.infenia.yukta.testing.Fixtures;
import java.util.List;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

/** What a definition declares is kept immutable, and absent means none. */
@NoArgsConstructor
class SupervisorRefTest {

  @Test
  void enabledUnlessSaidOtherwise() {
    assertThat(new SupervisorRef("g", null, null).runs()).isTrue();
    assertThat(new SupervisorRef("g", null, true).runs()).isTrue();
    assertThat(new SupervisorRef("g", null, false).runs()).isFalse();
  }

  @Test
  void emptyNodesMeanTheWholeWorkflow() {
    assertThat(new SupervisorRef("g", List.of(), null).nodes()).isNull();
    assertThat(new SupervisorRef("g", null, null).nodes()).isNull();
    assertThat(new SupervisorRef("g", List.of("a.*"), null).nodes()).containsExactly("a.*");
  }

  @Test
  void definitionsWithoutDeclarationsHaveNone() {
    assertThat(Fixtures.workflow("w", "d", List.of(), List.of()).supervisors()).isEmpty();
    assertThat(Fixtures.workflow("w", "d", List.of(), List.of(), null).supervisors()).isEmpty();
    assertThat(new SessionConfigData("s", "n", "d", "i", Map.of(), ".", Map.of()).supervisors())
        .isEmpty();
    assertThat(
            new SessionConfigData("s", "n", "d", "i", Map.of(), ".", Map.of(), null).supervisors())
        .isEmpty();
    assertThat(new SessionConfigResponse("s", "n", "d", "i", Map.of(), ".", Map.of()).supervisors())
        .isEmpty();
    assertThat(
            new SessionConfigResponse("s", "n", "d", "i", Map.of(), ".", Map.of(), null)
                .supervisors())
        .isEmpty();
  }

  @Test
  void definitionsWriteAssignmentsAndStillReadTheOldSupervisorsKey() {
    final tools.jackson.databind.json.JsonMapper json =
        tools.jackson.databind.json.JsonMapper.builder().build();
    final String old =
        "{\"workflowId\":\"w\",\"description\":\"d\",\"nodes\":[],\"edges\":[],"
            + "\"supervisors\":[{\"supervisor\":\"guard\"}]}";
    final WorkflowDefinition read = json.readValue(old, WorkflowDefinition.class);

    assertThat(read.supervisors()).extracting(SupervisorRef::supervisor).containsExactly("guard");
    assertThat(json.writeValueAsString(read))
        .contains("\"assignments\"")
        .doesNotContain("\"supervisors\"");
  }
}
