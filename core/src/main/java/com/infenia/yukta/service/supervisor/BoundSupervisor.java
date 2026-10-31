// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor;

import com.infenia.yukta.plugin.control.ControlRequest;
import com.infenia.yukta.plugin.control.Decision;
import com.infenia.yukta.plugin.control.ExecutionControlCommand;
import com.infenia.yukta.plugin.control.ExecutionEvent;
import com.infenia.yukta.plugin.control.ExecutionSupervisor;
import com.infenia.yukta.plugin.control.SupervisorBinding;
import com.infenia.yukta.plugin.control.SupervisorScope;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * A supervisor made from a profile and attached by a binding: it answers to the binding's id and
 * scope, and does what the supervisor made by the factory does.
 *
 * @param binding the binding that attached it
 * @param delegate the supervisor the factory made
 * @param dryRun whether its commands are only recorded
 */
public record BoundSupervisor(
    SupervisorBinding binding, ExecutionSupervisor delegate, boolean dryRun)
    implements ExecutionSupervisor {

  @Override
  @SuppressWarnings("PMD.ShortMethodName")
  public String id() {
    return binding.id();
  }

  @Override
  public int order() {
    return delegate.order();
  }

  @Override
  public SupervisorScope scope() {
    return binding.scope();
  }

  @Override
  public Flux<ExecutionControlCommand> supervise(final Flux<ExecutionEvent> events) {
    return delegate.supervise(events);
  }

  @Override
  public Mono<Decision> review(final ControlRequest request) {
    return delegate.review(request);
  }
}
