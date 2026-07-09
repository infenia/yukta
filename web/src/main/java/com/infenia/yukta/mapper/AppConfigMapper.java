// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mapper;

import com.infenia.yukta.dto.request.ConfigRequest;
import com.infenia.yukta.dto.request.WorkflowDefinitionRequest;
import com.infenia.yukta.dto.request.WorkflowDefinitionRequest.EdgeRequest;
import com.infenia.yukta.dto.request.WorkflowDefinitionRequest.NodeRequest;
import com.infenia.yukta.model.session.SessionConfigData;
import com.infenia.yukta.model.workflow.WorkflowDefinition;
import com.infenia.yukta.model.workflow.WorkflowDefinition.Edge;
import com.infenia.yukta.model.workflow.WorkflowDefinition.Node;
import org.mapstruct.Mapper;

/** Mapper for converting between API DTOs and internal domain models. */
@Mapper(componentModel = "spring")
public interface AppConfigMapper {

  /**
   * Map ConfigRequest to SessionConfigData.
   *
   * @param request the config request
   * @return the session config data
   */
  SessionConfigData toData(ConfigRequest request);

  /**
   * Map WorkflowDefinitionRequest to WorkflowDefinition.
   *
   * @param request the workflow definition request
   * @return the workflow definition
   */
  WorkflowDefinition toWorkflowDefinition(WorkflowDefinitionRequest request);

  /**
   * Map NodeRequest to Node.
   *
   * @param nodeRequest the node request
   * @return the node
   */
  Node toNode(NodeRequest nodeRequest);

  /**
   * Map EdgeRequest to Edge.
   *
   * @param edgeRequest the edge request
   * @return the edge
   */
  Edge toEdge(EdgeRequest edgeRequest);
}
