/*
 * Copyright 2026 Infenia Private Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
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
