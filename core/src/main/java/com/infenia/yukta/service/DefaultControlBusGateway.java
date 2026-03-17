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
package com.infenia.yukta.service;

import com.infenia.yukta.plugin.core.WorkflowPlugin;
import com.infenia.yukta.plugin.gateway.ControlBusGateway;
import com.infenia.yukta.plugin.message.Message;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * Default implementation of the {@link ControlBusGateway} that delegates to the {@link
 * ControlBusService}.
 */
@Service
@RequiredArgsConstructor
public class DefaultControlBusGateway implements ControlBusGateway {

  private final ControlBusService controlBusService;

  @Override
  public <T> Mono<Void> emit(final Message<T> signal) {
    return controlBusService.emit(signal);
  }

  @Override
  public void registerPlugin(final String nodeId, final WorkflowPlugin plugin) {
    controlBusService.registerPlugin(nodeId, plugin);
  }

  @Override
  public void unregisterPlugin(final String nodeId) {
    controlBusService.unregisterPlugin(nodeId);
  }

  @Override
  public Mono<Message<?>> sendCommand(final String nodeId, final Message<?> command) {
    return controlBusService.sendCommand(nodeId, command);
  }

  @Override
  public Message<?> getLastHeartbeat(final String nodeId) {
    return controlBusService.getLastHeartbeat(nodeId);
  }

  @Override
  public Message<?> getLastStatistics(final String nodeId) {
    return controlBusService.getLastStatistics(nodeId);
  }

  @Override
  public List<String> getActiveNodes() {
    return controlBusService.getActiveNodes();
  }
}
