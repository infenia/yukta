// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.runtime;

import com.infenia.yukta.plugin.control.ControlActor;
import com.infenia.yukta.security.AuditRecord;
import com.infenia.yukta.security.AuditSink;
import com.infenia.yukta.service.log.ExecutionLogEvent;
import com.infenia.yukta.service.log.ExecutionLogStore;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;

/**
 * Records the outcome of a control command in the execution log, with the actor, and passes it to
 * the {@link AuditSink} if there is one. The one place that writes control audit entries.
 */
@Slf4j
@UtilityClass
public class ControlAudit {

  /**
   * The channel a command arrived on, from the Reactor context; {@code SYSTEM} when none was set,
   * because only the entry that applies commands for people and engines writes one.
   *
   * @param context the Reactor context of the command
   * @return {@code OPERATOR}, {@code ENGINE} or {@code SYSTEM}
   */
  public static String channel(final reactor.util.context.ContextView context) {
    final Object channel = context.getOrDefault(ControlActor.CHANNEL_KEY, null);
    return channel == null ? ControlActor.Channel.SYSTEM.name() : channel.toString();
  }

  /**
   * Record a command.
   *
   * @param executionLog the execution log, the record behind the control audit API
   * @param sink the extra sink, or null
   * @param entry what happened to the command
   */
  @SuppressWarnings("PMD.AvoidCatchingGenericException")
  public static void record(
      final ExecutionLogStore executionLog,
      @Nullable final AuditSink sink,
      final AuditRecord entry) {
    final String logged =
        entry.actor() == null ? entry.command() : entry.command() + " [by " + entry.actor() + "]";
    executionLog.append(
        ExecutionLogEvent.control(
            entry.applied(),
            entry.executionId(),
            logged,
            entry.command(),
            entry.actor(),
            entry.reason(),
            entry.channel()));
    if (sink != null) {
      try {
        sink.record(entry);
      } catch (RuntimeException e) {
        log.atWarn().setCause(e).log("The audit sink failed; the command is not affected");
      }
    }
  }
}
