// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.review;

import com.infenia.yukta.plugin.control.ControlRequest;
import com.infenia.yukta.plugin.control.Decision;
import com.infenia.yukta.plugin.control.ExecutionControlCommand;
import com.infenia.yukta.plugin.control.ExecutionEvent;
import com.infenia.yukta.plugin.control.ExecutionSupervisor;
import com.infenia.yukta.plugin.control.SupervisorAttachment;
import com.infenia.yukta.plugin.control.SupervisorCapabilities;
import com.infenia.yukta.plugin.control.SupervisorScope;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * A supervisor made from a supervisor and attached by a attachment: it answers to the attachment's
 * id and scope, and does what the supervisor made by the factory does.
 *
 * @param attachment the attachment that attached it
 * @param delegate the supervisor the factory made
 * @param dryRun whether its commands are only recorded
 * @param capabilities which commands it may send and whether it may answer input requests
 * @param observeInternal whether it also sees the events of the internal session its scope names
 * @param scope what it owns, as matched at run time: the attachment's scope with its {@code group:}
 *     selectors resolved to the nodes they name now
 */
public record BoundSupervisor(
    SupervisorAttachment attachment,
    ExecutionSupervisor delegate,
    boolean dryRun,
    SupervisorCapabilities capabilities,
    boolean observeInternal,
    SupervisorScope scope)
    implements ExecutionSupervisor {

  /**
   * A bound supervisor that owns its attachment's scope as written.
   *
   * @param attachment the attachment that attached it
   * @param delegate the supervisor the factory made
   * @param dryRun whether its commands are only recorded
   * @param capabilities which commands it may send and whether it may answer input requests
   * @param observeInternal whether it also sees the events of the internal session its scope names
   */
  public BoundSupervisor(
      final SupervisorAttachment attachment,
      final ExecutionSupervisor delegate,
      final boolean dryRun,
      final SupervisorCapabilities capabilities,
      final boolean observeInternal) {
    this(attachment, delegate, dryRun, capabilities, observeInternal, attachment.scope());
  }

  @Override
  @SuppressWarnings("PMD.ShortMethodName")
  public String id() {
    return attachment.id();
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
