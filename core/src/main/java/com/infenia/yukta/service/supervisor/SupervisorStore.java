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
 * Keeps supervisor profiles and bindings, so those attached at run time can come back after a
 * restart when the store is durable. The default keeps them in memory only.
 */
public interface SupervisorStore {

  /**
   * All profiles.
   *
   * @return the profiles, ordered by id
   */
  Flux<Supervisor> supervisors();

  /**
   * Saves a profile, replacing one with the same id.
   *
   * @param profile the profile
   * @return completes when saved
   */
  Mono<Void> saveSupervisor(Supervisor profile);

  /**
   * Removes a profile.
   *
   * @param id the profile id
   * @return true if there was one
   */
  Mono<Boolean> deleteSupervisor(String id);

  /**
   * All bindings.
   *
   * @return the bindings, ordered by id
   */
  Flux<SupervisorAttachment> attachments();

  /**
   * Saves a binding, replacing one with the same id.
   *
   * @param binding the binding
   * @return completes when saved
   */
  Mono<Void> saveAttachment(SupervisorAttachment binding);

  /**
   * Removes a binding.
   *
   * @param id the binding id
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
