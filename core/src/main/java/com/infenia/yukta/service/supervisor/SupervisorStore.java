// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor;

import com.infenia.yukta.model.supervisor.SupervisorRef;
import com.infenia.yukta.plugin.control.Supervisor;
import com.infenia.yukta.plugin.control.SupervisorAttachment;
import java.util.List;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Keeps supervisor supervisors and attachments, so those attached at run time can come back after a
 * restart when the store is durable. The default keeps them in memory only.
 */
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
}
