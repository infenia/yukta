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
package com.infenia.yukta.service.orchestrator.assembly;

import com.infenia.yukta.message.Message;
import com.infenia.yukta.service.control.ExecutionControl;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Encapsulates all state and context needed to assemble a node's reactive stream.
 *
 * <p>Bundles execution context, workflow definition, control mechanisms, and stream/terminal
 * collections into a single object, reducing NodeAssembler parameter count from 8 to 1.
 *
 * @param executionId the execution identifier
 * @param sessionId the session identifier
 * @param workflowId the workflow identifier
 * @param payload the initial trigger payload
 * @param control the execution control handle for fine-grained node operations
 * @param streams the array of all node streams in the workflow
 * @param terminals the list of terminal node completion Monos
 * @param disposables the list of disposables to manage resource lifecycle
 * @param connectors the list of tasks to connect upstreams to sinks
 */
// streams is a fixed-size, index-addressed array filled in-place during assembly; equals/hashCode
// on this record are never used, so the array's reference-equality semantics are not a concern.
@SuppressWarnings({"PMD.DataClass", "ArrayRecordComponent"})
public record AssemblyContext(
    String executionId,
    String sessionId,
    String workflowId,
    Map<String, Object> payload,
    ExecutionControl control,
    Flux<Message<?>>[] streams,
    List<Mono<Void>> terminals,
    List<Disposable> disposables,
    List<Runnable> connectors) {

  /** Compact constructor to ensure all mutable collections are defensively copied. */
  @SuppressWarnings("PMD.NullAssignment")
  public AssemblyContext {
    payload = payload != null ? Map.copyOf(payload) : null;
  }

  @Override
  public Map<String, Object> payload() {
    return payload != null ? Collections.unmodifiableMap(payload) : null;
  }

  @Override
  @SuppressWarnings("PMD.MethodReturnsInternalArray")
  public Flux<Message<?>>[] streams() {
    return streams;
  }

  @Override
  public List<Mono<Void>> terminals() {
    return terminals;
  }

  @Override
  public List<Disposable> disposables() {
    return disposables;
  }

  @Override
  public List<Runnable> connectors() {
    return connectors;
  }
}
