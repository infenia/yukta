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
package com.infenia.yukta.service.execution.status;

import jakarta.validation.constraints.NotNull;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Publishes execution status events from the orchestrator.
 *
 * <p>This interface decouples the orchestrator from the control bus. The orchestrator publishes
 * status events (RUNNING, SUCCESS, FAILURE) through this publisher, and the control bus (or other
 * listeners) subscribes to receive updates.
 *
 * <p>This breaks the circular dependency: Orchestrator → ExecutionStatusPublisher ←
 * ControlBusGateway
 */
public interface ExecutionStatusPublisher {

  /**
   * Publish a status event.
   *
   * @param event the execution status event
   * @return a Mono that completes when the event is published
   */
  Mono<Void> publishStatus(@NotNull ExecutionStatusEvent event);

  /**
   * Get a stream of all status events (for internal use by the control bus).
   *
   * @return a Flux of status events
   */
  Flux<ExecutionStatusEvent> statusStream();
}
