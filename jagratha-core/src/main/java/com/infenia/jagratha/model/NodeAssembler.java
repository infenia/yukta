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
package com.infenia.jagratha.model;

import com.infenia.jagratha.plugin.Message;
import java.util.List;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * A functional interface for reifying a single node's reactive stream during workflow
 * instantiation.
 */
@FunctionalInterface
public interface NodeAssembler {
  /**
   * Assembles the reactive stream for a node.
   *
   * @param executionId the execution identifier
   * @param streams the array of all node streams in the workflow
   * @param terminals the list of terminal node completion Monos
   */
  void assemble(String executionId, Flux<Message>[] streams, List<Mono<Void>> terminals);
}
