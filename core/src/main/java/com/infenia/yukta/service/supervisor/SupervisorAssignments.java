// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor;

import com.infenia.yukta.config.YuktaProperties;
import com.infenia.yukta.plugin.control.ControlActor;
import com.infenia.yukta.plugin.control.SupervisorAttachment;
import com.infenia.yukta.plugin.control.SupervisorScope;
import com.infenia.yukta.plugin.control.SupervisorSource;
import com.infenia.yukta.service.ConflictException;
import com.infenia.yukta.service.NotFoundException;
import com.infenia.yukta.service.supervisor.SettingsValidationException.Issue;
import com.infenia.yukta.service.supervisor.ownership.OwnershipConflict;
import com.infenia.yukta.service.supervisor.ownership.OwnershipConflictException;
import com.infenia.yukta.service.supervisor.ownership.OwnershipValidator;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * What a supervisor owns, set as a whole: the plan's {@code assignments}. An assignment is a stored
 * attachment of source {@code MANUAL} with an id derived from the supervisor and the target, so
 * setting the same list twice changes nothing. Assignments from definitions ({@code DEFINITION})
 * and the server ({@code SYSTEM}) are listed but changed only where they come from.
 *
 * <p>Setting checks everything before it changes anything: each target, then the one-owner rule
 * against every other supervisor ({@code 409 OWNERSHIP_CONFLICT}), then the locks. A locked
 * assignment can be changed or removed only by asking to unlock, and only by a key named in {@code
 * yukta.supervisor.lock-holders} when that list is set.
 */
@Component
public class SupervisorAssignments {

  /** The prefix of the ids of assignments. */
  public static final String ID_PREFIX = "asg-";

  /** Hex characters of the hash in an assignment id. */
  private static final int HASH_CHARS = 16;

  /** The prefix of an API key's actor. */
  private static final String KEY_ACTOR = "key:";

  private final SupervisorService service;

  private List<String> lockHolders = List.of();

  /**
   * Creates the assignments.
   *
   * @param service the supervisors and their attachments
   */
  public SupervisorAssignments(final SupervisorService service) {
    this.service = service;
  }

  /**
   * Reads who may change a locked assignment.
   *
   * @param properties the settings
   */
  @Autowired(required = false)
  public void setProperties(final YuktaProperties properties) {
    this.lockHolders = List.copyOf(properties.supervisor().lockHolders());
  }

  /**
   * One thing a supervisor should own.
   *
   * @param scope what it owns
   * @param enabled whether it runs
   * @param locked whether only a lock holder may change or remove it
   */
  public record Assignment(SupervisorScope scope, boolean enabled, boolean locked) {

    /** Rejects a missing scope. */
    public Assignment {
      Objects.requireNonNull(scope, "scope");
    }
  }

  /**
   * The id of the assignment of a supervisor to a target: the same for the same pair, whatever else
   * changes.
   *
   * @param supervisorId the supervisor
   * @param scope the target
   * @return the id
   */
  public static String idOf(final String supervisorId, final SupervisorScope scope) {
    try {
      final byte[] digest =
          MessageDigest.getInstance("SHA-256")
              .digest((supervisorId + "\n" + scope.key()).getBytes(StandardCharsets.UTF_8));
      return ID_PREFIX + HexFormat.of().formatHex(digest).substring(0, HASH_CHARS);
    } catch (final NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is part of every Java runtime", e);
    }
  }

  /**
   * Every assignment of a supervisor, whatever made it.
   *
   * @param supervisorId the supervisor
   * @return its attachments; empty for an unknown supervisor
   */
  public Flux<SupervisorAttachment> of(final String supervisorId) {
    return service.attachments().filter(a -> a.supervisorId().equals(supervisorId));
  }

  /**
   * What would be wrong with setting these assignments, without changing anything.
   *
   * @param supervisorId the supervisor
   * @param wanted what it should own
   * @return the problems, each with a field {@code assignments[i]}; empty when it may be set
   */
  public Mono<List<Issue>> check(final String supervisorId, final List<Assignment> wanted) {
    return problems(supervisorId, wanted).map(Problems::all);
  }

  /** The bad targets, and the overlaps with other supervisors, apart. */
  private record Problems(
      List<Issue> issues, List<Issue> overlaps, List<OwnershipConflict> conflicts) {

    /** Every problem, in the order of the assignments sent. */
    private List<Issue> all() {
      return Stream.concat(issues.stream(), overlaps.stream())
          .sorted(java.util.Comparator.comparingInt(Problems::index))
          .toList();
    }

    private static int index(final Issue issue) {
      final String field = issue.field();
      return Integer.parseInt(field.substring(field.indexOf('[') + 1, field.indexOf(']')));
    }
  }

  private Mono<Problems> problems(final String supervisorId, final List<Assignment> wanted) {
    return service
        .supervisors()
        .any(s -> s.id().equals(supervisorId))
        .flatMap(
            known ->
                known
                    ? service.attachments().collectList()
                    : Mono.error(new NotFoundException("Supervisor not found: " + supervisorId)))
        .flatMap(all -> problems(supervisorId, wanted, all));
  }

  private Mono<Problems> problems(
      final String supervisorId,
      final List<Assignment> wanted,
      final List<SupervisorAttachment> all) {
    final List<SupervisorAttachment> others =
        all.stream()
            .filter(SupervisorAttachment::enabled)
            .filter(a -> !a.supervisorId().equals(supervisorId))
            .toList();
    final List<SupervisorScope> scopes =
        Stream.concat(wanted.stream().map(Assignment::scope), others.stream().map(a -> a.scope()))
            .toList();
    return service
        .validatorFor(scopes)
        .map(validator -> problems(supervisorId, wanted, others, validator));
  }

  /**
   * Makes a supervisor own exactly these targets (its {@code MANUAL} assignments), after every
   * check passed. Assignments that stay the same are left running.
   *
   * @param supervisorId the supervisor
   * @param wanted what it should own
   * @param unlock whether the caller asks to change or remove locked assignments
   * @return the supervisor's assignments afterwards; errors with {@link NotFoundException}, {@link
   *     SettingsValidationException} for a bad target, {@link OwnershipConflictException} for a
   *     target another supervisor owns, or {@link ConflictException} for a locked one
   */
  public Mono<List<SupervisorAttachment>> set(
      final String supervisorId, final List<Assignment> wanted, final boolean unlock) {
    return problems(supervisorId, wanted)
        .flatMap(
            found -> {
              if (!found.conflicts().isEmpty()) {
                return Mono.error(new OwnershipConflictException(found.conflicts()));
              }
              if (!found.issues().isEmpty()) {
                return Mono.error(new SettingsValidationException(found.issues()));
              }
              return Mono.deferContextual(
                  context ->
                      apply(
                          supervisorId,
                          wanted,
                          unlock,
                          context.<String>getOrEmpty(ControlActor.CONTEXT_KEY).orElse(null)));
            })
        .thenMany(Flux.defer(() -> of(supervisorId)))
        .collectList();
  }

  private Mono<Void> apply(
      final String supervisorId,
      final List<Assignment> wanted,
      final boolean unlock,
      final @Nullable String actor) {
    final Map<String, SupervisorAttachment> desired = new HashMap<>();
    for (final Assignment assignment : wanted) {
      final String id = idOf(supervisorId, assignment.scope());
      desired.put(
          id,
          new SupervisorAttachment(id, supervisorId, assignment.scope(), assignment.enabled())
              .withLocked(assignment.locked()));
    }
    return of(supervisorId)
        .filter(a -> a.source() == SupervisorSource.MANUAL)
        .collectList()
        .flatMap(
            existing -> {
              final List<SupervisorAttachment> touchedLocks =
                  existing.stream()
                      .filter(SupervisorAttachment::locked)
                      .filter(a -> !a.equals(desired.get(a.id())))
                      .toList();
              if (!touchedLocks.isEmpty() && !mayUnlock(unlock, actor)) {
                return Mono.error(new ConflictException(lockedMessage(touchedLocks)));
              }
              final Set<String> keep = desired.keySet();
              final Flux<Void> removals =
                  Flux.fromIterable(existing)
                      .filter(a -> !keep.contains(a.id()))
                      .concatMap(a -> service.detachUnlocked(a.id()));
              final Flux<SupervisorAttachment> additions =
                  Flux.fromIterable(desired.values())
                      .filter(a -> !existing.contains(a))
                      .concatMap(service::attach);
              return removals.thenMany(additions).then();
            });
  }

  private boolean mayUnlock(final boolean unlock, final @Nullable String actor) {
    if (!unlock) {
      return false;
    }
    return lockHolders.isEmpty()
        || actor != null
            && actor.startsWith(KEY_ACTOR)
            && lockHolders.contains(actor.substring(KEY_ACTOR.length()));
  }

  private String lockedMessage(final List<SupervisorAttachment> locked) {
    final String targets = String.join(", ", locked.stream().map(a -> a.scope().key()).toList());
    return lockHolders.isEmpty()
        ? "Locked assignments (" + targets + ") change only when the request asks to unlock"
        : "Locked assignments ("
            + targets
            + ") change only with unlock, by a key in yukta.supervisor.lock-holders";
  }

  private static Problems problems(
      final String supervisorId,
      final List<Assignment> wanted,
      final List<SupervisorAttachment> others,
      final OwnershipValidator validator) {
    final List<Issue> found = new ArrayList<>();
    final List<Issue> overlaps = new ArrayList<>();
    final List<OwnershipConflict> conflicts = new ArrayList<>();
    final Set<String> seen = new HashSet<>();
    final List<OwnershipValidator.Claim> claims =
        others.stream()
            .map(a -> new OwnershipValidator.Claim(a.supervisorId(), a.scope()))
            .toList();
    for (int i = 0; i < wanted.size(); i++) {
      final Assignment assignment = wanted.get(i);
      final String field = "assignments[" + i + "]";
      if (!seen.add(assignment.scope().key())) {
        found.add(new Issue(field, "is listed twice"));
      } else if (SupervisorService.groupsWithoutSession(assignment.scope())) {
        found.add(new Issue(field, SupervisorService.GROUPS_NEED_SESSION));
      } else if (assignment.enabled()) {
        for (final OwnershipConflict conflict :
            validator.conflicts(
                new OwnershipValidator.Claim(supervisorId, assignment.scope()), claims)) {
          overlaps.add(new Issue(field, conflict.message()));
          conflicts.add(conflict);
        }
      }
    }
    return new Problems(found, overlaps, conflicts);
  }
}
