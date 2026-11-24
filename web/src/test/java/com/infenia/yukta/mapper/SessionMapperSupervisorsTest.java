// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.dto.request.ConfigRequest;
import com.infenia.yukta.dto.request.WorkflowDefinitionRequest;
import com.infenia.yukta.dto.request.WorkflowDefinitionRequest.NodeRequest;
import com.infenia.yukta.model.session.SessionConfigData;
import com.infenia.yukta.model.supervisor.SupervisorRef;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

/** Tests that declared supervisors survive the SessionMapper mapping. */
class SessionMapperSupervisorsTest {

  /** Workflow identifier for testing. */
  private static final String WORKFLOW_ID_1 = "workflow-1";

  /** Node identifier for testing. */
  private static final String NODE_ID_1 = "node-1";

  /** Processor plugin type for testing. */
  private static final String PROCESSOR_PLUGIN = "ProcessorPlugin";

  /** Mapper for session data transformation. */
  private SessionMapper mapper;

  @BeforeEach
  void setUp() {
    mapper = Mappers.getMapper(SessionMapper.class);
  }

  @Test
  void declaredSupervisorsOfSessionAndWorkflowSurviveTheMapping() {
    final SupervisorRef whole = new SupervisorRef("guard", null, null);
    final SupervisorRef section = new SupervisorRef("watch", List.of("build.*"), false);
    final ConfigRequest request =
        new ConfigRequest(
            "s1",
            "n",
            "d",
            "i",
            Map.of(),
            "/tmp",
            Map.of(
                WORKFLOW_ID_1,
                new WorkflowDefinitionRequest(
                    WORKFLOW_ID_1,
                    "w",
                    List.of(new NodeRequest(NODE_ID_1, PROCESSOR_PLUGIN, Map.of())),
                    List.of(),
                    List.of(section))),
            List.of(whole));

    final SessionConfigData result = mapper.configRequestToSessionConfigData(request);

    assertThat(result.supervisors()).containsExactly(whole);
    assertThat(result.workflows().get(WORKFLOW_ID_1).supervisors()).containsExactly(section);
  }
}
