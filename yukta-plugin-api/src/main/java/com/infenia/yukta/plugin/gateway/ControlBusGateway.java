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
import reactor.core.publisher.Mono;

/**
 * Interface for components to interact with the system's Control Bus.
 *
 * <p>The Control Bus manages administrative signals such as heartbeats, statistics, and
 * configuration updates.
 */
@FunctionalInterface
public interface ControlBusGateway {

  /**
   * Emit a control message to the bus.
   *
   * @param <T> the type of the control payload
   * @param signal the control message to emit
   * @return a Mono that completes when the signal has been emitted
   */
  <T> Mono<Void> emit(Message<T> signal);
}
