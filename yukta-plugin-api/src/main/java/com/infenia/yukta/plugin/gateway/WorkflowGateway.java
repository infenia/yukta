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
package com.infenia.yukta.plugin.gateway;

import com.infenia.yukta.plugin.message.Message;
import java.util.List;
import java.util.Map;
import reactor.core.publisher.Mono;

/**
 * Gateway for executing workflows (including sub-workflows).
 *
 * <p>Encapsulates the complexity of preparation, orchestration, and execution context.
 */
public interface WorkflowGateway extends MessagingGateway {

  /**
   * Execute a sub-workflow with a given payload.
   *
   * @param parentSessionId the session ID of the parent workflow
   * @param childSessionId the session ID to use for the child workflow
   * @param workflowId the ID of the workflow to execute
   * @param payload the initial trigger payload
   * @return a Mono containing the collected result messages from the sub-workflow
   */
  Mono<List<Message<?>>> executeSubWorkflow(
      String parentSessionId,
      String childSessionId,
      String workflowId,
      Map<String, Object> payload);
}
