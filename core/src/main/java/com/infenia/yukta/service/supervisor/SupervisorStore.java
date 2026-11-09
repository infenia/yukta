// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor;

import com.infenia.yukta.model.supervisor.SupervisorRef;
import com.infenia.yukta.plugin.control.Supervisor;
import com.infenia.yukta.plugin.control.SupervisorAttachment;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Keeps supervisor supervisors and attachments, so those attached at run time can come back after a
 * restart when the store is durable. The default keeps them in memory only.
 */
@SuppressWarnings("PMD.TooManyMethods") // one store for supervisors and what belongs to them
public interface SupervisorStore {

  /**
   * All supervisors.
   *
   * @return the supervisors, ordered by id
   */
  Flux<Supervisor> supervisors();

  /**
   * Saves a supervisor, replacing one with the same id.
   *
   * @param supervisor the supervisor
   * @return completes when saved
   */
  Mono<Void> saveSupervisor(Supervisor supervisor);

  /**
   * Removes a supervisor.
   *
   * @param id the supervisor id
   * @return true if there was one
   */
  Mono<Boolean> deleteSupervisor(String id);

  /**
   * All attachments.
   *
   * @return the attachments, ordered by id
   */
  Flux<SupervisorAttachment> attachments();

  /**
   * Saves a attachment, replacing one with the same id.
   *
   * @param attachment the attachment
   * @return completes when saved
   */
  Mono<Void> saveAttachment(SupervisorAttachment attachment);

  /**
   * Removes a attachment.
   *
   * @param id the attachment id
   * @return true if there was one
   */
  Mono<Boolean> deleteAttachment(String id);

  /**
   * The supervisors a session declares for itself as a whole, as it was last saved.
   *
   * @param sessionId the session
   * @return the declared supervisors; empty when it declares none
   */
  Mono<List<SupervisorRef>> sessionRefs(String sessionId);

  /**
   * Keeps the supervisors a session declares for itself as a whole; an empty list forgets them.
   *
   * @param sessionId the session
   * @param refs the declared supervisors
   * @return completes when kept
   */
  Mono<Void> saveSessionRefs(String sessionId, List<SupervisorRef> refs);

  /**
   * Keeps a saved state of a supervisor as its next version, unless it equals the newest one, and
   * drops the versions beyond the newest {@code keep}.
   *
   * @param supervisor the supervisor as saved
   * @param savedAt when it was saved
   * @param keep how many versions to keep
   * @return completes when kept
   */
  Mono<Void> saveVersion(Supervisor supervisor, Instant savedAt, int keep);

  /**
   * The kept versions of a supervisor.
   *
   * @param id the supervisor id
   * @return newest first; empty for an unknown supervisor
   */
  Flux<SupervisorVersions.Version> versions(String id);

  /**
   * Forgets the versions of a supervisor.
   *
   * @param id the supervisor id
   * @return completes when forgotten
   */
  Mono<Void> deleteVersions(String id);

  /**
   * The runs a supervisor paused and nobody resumed or stopped yet (the hold ledger).
   *
   * @return the holds, oldest first per holder
   */
  Flux<Hold> holds();

  /**
   * Remembers that a supervisor holds a run.
   *
   * @param hold the hold
   * @return completes when kept
   */
  Mono<Void> saveHold(Hold hold);

  /**
   * Forgets holds of a run.
   *
   * @param holder the supervisor or attachment id; {@code null} for every holder
   * @param executionId the run
   * @return completes when forgotten
   */
  Mono<Void> deleteHold(@Nullable String holder, String executionId);

  /**
   * A run paused by a supervisor.
   *
   * @param holder the supervisor or attachment id that paused it
   * @param executionId the run
   */
  record Hold(String holder, String executionId) {}
}
