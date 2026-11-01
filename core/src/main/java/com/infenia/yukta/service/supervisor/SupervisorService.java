// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor;

import com.infenia.yukta.plugin.control.ControlActor;
import com.infenia.yukta.plugin.control.ExecutionSupervisor;
import com.infenia.yukta.plugin.control.SupervisorBinding;
import com.infenia.yukta.plugin.control.SupervisorFactory;
import com.infenia.yukta.plugin.control.SupervisorIds;
import com.infenia.yukta.plugin.control.SupervisorProfile;
import com.infenia.yukta.security.AuditRecord;
import com.infenia.yukta.security.AuditSink;
import com.infenia.yukta.service.ConflictException;
import com.infenia.yukta.service.NotFoundException;
import com.infenia.yukta.service.control.ControlAudit;
import com.infenia.yukta.service.log.ExecutionLogStore;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

/**
 * Creates, changes and removes supervisor profiles and attaches them to a scope at run time.
 *
 * <p>A supervisor attached while runs are going sees the events from then on and reviews the
 * commands from then on, so a run is supervised from the moment of attachment, not from its start.
 * Every attach and detach is recorded in the execution log under the run id {@value #AUDIT_RUN}.
 */
@Slf4j
@Service
@SuppressWarnings({"PMD.TooManyMethods", "PMD.ExcessiveImports"})
public class SupervisorService {

  /** Run id under which attaching and detaching is recorded. */
  public static final String AUDIT_RUN = "supervisors";

  /** Most bindings; each is a supervisor with its own subscription and metric tag. */
  public static final int MAX_BINDINGS = 50;

  /** Attaches in progress, so the limit holds when several run at once. */
  private final AtomicInteger attaching = new AtomicInteger();

  /** Where profiles and bindings are kept. */
  private final SupervisorStore store;

  /** The factories by supervisor type. */
  private final Map<String, SupervisorFactory> factories;

  /** Runs the attached supervisors. */
  private final SupervisorHost host;

  /** Records attach and detach. */
  private final ExecutionLogStore executionLog;

  /** Receives every audit record besides the log, if a module provides one. */
  @Nullable private AuditSink auditSink;

  /**
   * Creates the service.
   *
   * @param store where profiles and bindings are kept
   * @param factories the supervisor factories
   * @param host runs the attached supervisors
   * @param executionLog records attach and detach
   */
  @SuppressFBWarnings("EI_EXPOSE_REP2")
  public SupervisorService(
      final SupervisorStore store,
      final List<SupervisorFactory> factories,
      final SupervisorHost host,
      final ExecutionLogStore executionLog) {
    this.store = store;
    this.factories =
        factories.stream().collect(Collectors.toMap(SupervisorFactory::type, Function.identity()));
    this.host = host;
    this.executionLog = executionLog;
  }

  /**
   * Passes every audit record on to a sink, besides the execution log.
   *
   * @param sink the sink, if the application has one
   */
  @Autowired(required = false)
  public void setAuditSink(final AuditSink sink) {
    this.auditSink = sink;
  }

  /** Starts the enabled bindings that were kept. */
  @EventListener(ApplicationReadyEvent.class)
  /* default */ void restore() {
    // not in @PostConstruct: the store may need its schema first, and an unreadable store is
    // retried
    Flux.defer(store::bindings)
        .retryWhen(Retry.backoff(5, Duration.ofSeconds(2)))
        .filter(SupervisorBinding::enabled)
        .filter(SupervisorService::validStoredId)
        .concatMap(
            binding ->
                store
                    .profiles()
                    .filter(profile -> profile.id().equals(binding.profileId()))
                    .next()
                    .doOnNext(profile -> start(binding, profile))
                    .onErrorResume(
                        error -> {
                          log.atWarn()
                              .setCause(error)
                              .addKeyValue("binding", binding.id())
                              .log("Could not restore the supervisor binding");
                          return Mono.empty();
                        }))
        .subscribe(
            _ -> {},
            error ->
                log.atError()
                    .setCause(error)
                    .log("Could not read the supervisor bindings; none were restored"));
  }

  /**
   * The supervisor types that can be used in a profile.
   *
   * @return the types, sorted
   */
  public List<String> types() {
    return factories.keySet().stream().sorted().toList();
  }

  /**
   * All profiles.
   *
   * @return the profiles
   */
  public Flux<SupervisorProfile> profiles() {
    return store.profiles();
  }

  /**
   * Creates or replaces a profile. Bindings that use it are restarted with the new settings.
   *
   * @param profile the profile
   * @return the saved profile; errors with {@link IllegalArgumentException} for an invalid id, an
   *     unknown type or invalid settings
   */
  public Mono<SupervisorProfile> saveProfile(final SupervisorProfile profile) {
    return Mono.fromRunnable(() -> validate(profile))
        .then(Mono.defer(() -> store.saveProfile(profile)))
        .then(
            store
                .bindings()
                .filter(binding -> binding.enabled() && binding.profileId().equals(profile.id()))
                .doOnNext(binding -> start(binding, profile))
                .then())
        .thenReturn(profile);
  }

  /**
   * Removes a profile that no binding uses.
   *
   * @param id the profile id
   * @return completes when removed; errors with {@link NotFoundException} or {@link
   *     ConflictException} when a binding still uses it
   */
  public Mono<Void> deleteProfile(final String id) {
    return store
        .bindings()
        .filter(binding -> binding.profileId().equals(id))
        .hasElements()
        .flatMap(
            used ->
                used
                    ? Mono.error(new ConflictException("Profile " + id + " is still attached"))
                    : store.deleteProfile(id))
        .flatMap(
            existed ->
                existed
                    ? Mono.<Void>empty()
                    : Mono.error(new NotFoundException("Profile not found: " + id)));
  }

  /**
   * All bindings.
   *
   * @return the bindings
   */
  public Flux<SupervisorBinding> bindings() {
    return store.bindings();
  }

  /**
   * Attaches a profile to a scope. An enabled binding starts at once.
   *
   * @param binding the binding
   * @return the binding; errors with {@link NotFoundException} for an unknown profile, {@link
   *     ConflictException} when there are {@value #MAX_BINDINGS} bindings, or {@link
   *     IllegalArgumentException} when the profile's settings no longer make a supervisor
   */
  public Mono<SupervisorBinding> attach(final SupervisorBinding binding) {
    try {
      SupervisorIds.validate(binding.id(), "binding id");
    } catch (IllegalArgumentException e) {
      return Mono.error(e);
    }
    if (host.isBuiltIn(binding.id())) {
      return Mono.error(
          new ConflictException(
              "A supervisor named '" + binding.id() + "' is already configured; pick another id"));
    }
    // counted before the store is read, so concurrent attaches cannot all pass the limit
    attaching.incrementAndGet();
    return attachChecked(binding).doFinally(_ -> attaching.decrementAndGet());
  }

  private Mono<SupervisorBinding> attachChecked(final SupervisorBinding binding) {
    return store
        .profiles()
        .filter(profile -> profile.id().equals(binding.profileId()))
        .next()
        .switchIfEmpty(
            Mono.error(new NotFoundException("Profile not found: " + binding.profileId())))
        .flatMap(
            profile ->
                store
                    .bindings()
                    .filter(existing -> !existing.id().equals(binding.id()))
                    .count()
                    .flatMap(
                        others ->
                            others + attaching.get() - 1 >= MAX_BINDINGS
                                ? Mono.error(
                                    new ConflictException(
                                        "At most " + MAX_BINDINGS + " supervisors can be attached"))
                                : Mono.just(profile)))
        .doOnNext(
            profile -> {
              if (binding.enabled()) {
                start(binding, profile);
              } else {
                host.detach(binding.id());
              }
            })
        .then(
            store
                .saveBinding(binding)
                .onErrorResume(error -> rollback(binding.id()).then(Mono.error(error))))
        .then(
            audited(true, "AttachSupervisor(" + binding.id() + ", " + binding.scope().key() + ")"))
        .thenReturn(binding);
  }

  /** Puts the host back to what the store holds for a binding when saving it failed. */
  private Mono<Void> rollback(final String bindingId) {
    return store
        .bindings()
        .filter(existing -> existing.id().equals(bindingId))
        .next()
        .map(Optional::of)
        .defaultIfEmpty(Optional.empty())
        .flatMap(
            previous ->
                previous
                    .filter(SupervisorBinding::enabled)
                    .map(
                        enabled ->
                            store
                                .profiles()
                                .filter(profile -> profile.id().equals(enabled.profileId()))
                                .next()
                                .doOnNext(profile -> start(enabled, profile))
                                .then())
                    .orElseGet(() -> Mono.fromRunnable(() -> host.detach(bindingId))))
        .onErrorResume(error -> Mono.empty());
  }

  /**
   * Detaches a binding and stops its supervisor.
   *
   * @param bindingId the binding id
   * @return completes when stopped; errors with {@link NotFoundException}
   */
  public Mono<Void> detach(final String bindingId) {
    return Mono.fromSupplier(() -> host.detach(bindingId))
        .then(store.deleteBinding(bindingId))
        .flatMap(
            existed ->
                existed
                    ? audited(true, "DetachSupervisor(" + bindingId + ")")
                    : Mono.error(new NotFoundException("Binding not found: " + bindingId)));
  }

  /**
   * Detaches every binding limited to a session, for when the session is deleted.
   *
   * @param sessionId the session id
   * @return completes when they are gone
   */
  public Mono<Void> removeForSession(final String sessionId) {
    return store
        .bindings()
        .filter(binding -> sessionId.equals(binding.scope().sessionId()))
        .concatMap(binding -> detach(binding.id()))
        .then();
  }

  private SupervisorFactory factory(final SupervisorProfile profile) {
    final SupervisorFactory factory = factories.get(profile.type());
    if (factory == null) {
      throw new IllegalArgumentException(
          "Unknown supervisor type '" + profile.type() + "'; use one of " + types());
    }
    return factory;
  }

  /** A stored binding with a bad id is skipped with a warning; the migration renames such ids. */
  private static boolean validStoredId(final SupervisorBinding binding) {
    final boolean valid = SupervisorIds.isValid(binding.id());
    if (!valid) {
      log.atWarn()
          .addKeyValue("binding", binding.id())
          .log("Skipped a supervisor binding whose id breaks the naming rule");
    }
    return valid;
  }

  private void validate(final SupervisorProfile profile) {
    SupervisorIds.validate(profile.id(), "profile id");
    factory(profile).validate(profile);
  }

  private ExecutionSupervisor create(final SupervisorProfile profile) {
    return factory(profile).create(profile);
  }

  private void start(final SupervisorBinding binding, final SupervisorProfile profile) {
    host.attach(new BoundSupervisor(binding, create(profile), profile.dryRun()));
  }

  /** Records an attach or detach with the caller as actor. */
  private Mono<Void> audited(final boolean applied, final String command) {
    return Mono.deferContextual(
        context ->
            Mono.fromRunnable(
                () ->
                    ControlAudit.record(
                        executionLog,
                        auditSink,
                        new AuditRecord(
                            applied,
                            AUDIT_RUN,
                            command,
                            context.getOrDefault(ControlActor.CONTEXT_KEY, null),
                            null,
                            Instant.now()))));
  }
}
