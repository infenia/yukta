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
