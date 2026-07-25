// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mapper;

import com.infenia.yukta.dto.request.ConfigRequest;
import com.infenia.yukta.dto.request.WorkflowDefinitionRequest;
import com.infenia.yukta.dto.response.SessionDetails;
import com.infenia.yukta.dto.response.SessionListItem;
import com.infenia.yukta.model.session.SessionConfigData;
import com.infenia.yukta.model.session.SessionConfigResponse;
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

  /**
   * Map session configuration response to a lean session list item for table display.
   *
   * <p>Extracts tags from the map (using keys only) and counts workflows, omitting full workflow
   * bodies.
   *
   * @param response the full session configuration response
   * @return a lean session list item suitable for tables/lists
   */
  @Mapping(target = "tags", expression = "java(java.util.List.copyOf(response.tags().keySet()))")
  @Mapping(target = "workflowCount", expression = "java(response.workflows().size())")
  SessionListItem sessionConfigResponseToSessionListItem(SessionConfigResponse response);

  /**
   * Map session configuration response to session details for the detail page.
   *
   * <p>Extracts all metadata (name, description, initiator, tags, projectPath) and workflow IDs
   * from the configuration.
   *
   * @param response the full session configuration response
   * @return session details including metadata and workflow IDs
   */
  @Mapping(target = "tags", expression = "java(java.util.List.copyOf(response.tags().keySet()))")
  @Mapping(
      target = "workflowIds",
      expression = "java(java.util.List.copyOf(response.workflows().keySet()))")
  SessionDetails sessionConfigResponseToSessionDetails(SessionConfigResponse response);
}
