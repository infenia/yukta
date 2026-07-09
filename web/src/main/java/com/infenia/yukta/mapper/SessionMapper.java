// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mapper;

import com.infenia.yukta.dto.request.ConfigRequest;
import com.infenia.yukta.dto.request.WorkflowDefinitionRequest;
import com.infenia.yukta.model.session.SessionConfigData;
import com.infenia.yukta.model.workflow.WorkflowDefinition;
import com.infenia.yukta.model.workflow.WorkflowDefinition.Edge;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/** MapStruct mapper for converting between web DTOs and core domain session models. */
@Mapper(componentModel = "spring")
public interface SessionMapper {

  /**
   * Map web ConfigRequest DTO to core SessionConfigData domain model.
   *
   * @param configRequest the web request DTO
   * @return the core domain model
   */
  SessionConfigData configRequestToSessionConfigData(ConfigRequest configRequest);

  /**
   * Map workflow definition request to domain model.
   *
   * @param request the workflow definition request
   * @return the domain model
   */
  WorkflowDefinition workflowDefinitionRequestToWorkflowDefinition(
      WorkflowDefinitionRequest request);

  /**
   * Map edge request to domain edge, applying default sourcePort value if null.
   *
   * @param edgeRequest the edge request
   * @return the domain edge with default sourcePort if necessary
   */
  @Mapping(target = "sourcePort", source = "sourcePort", defaultValue = "default")
  Edge edgeRequestToEdge(WorkflowDefinitionRequest.EdgeRequest edgeRequest);
}
