// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.input;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.infenia.yukta.plugin.control.ControlActor;
import com.infenia.yukta.plugin.control.ExecutionSupervisor;
import com.infenia.yukta.plugin.control.SupervisorBinding;
import com.infenia.yukta.plugin.control.SupervisorCapabilities;
import com.infenia.yukta.plugin.control.SupervisorScope;
import com.infenia.yukta.plugin.input.InputRequest;
import com.infenia.yukta.plugin.input.InputRequestSpec;
import com.infenia.yukta.plugin.input.InputResponse;
import com.infenia.yukta.plugin.input.InputRisk;
import com.infenia.yukta.security.ApiAuthenticator;
import com.infenia.yukta.security.ApiKeys;
import com.infenia.yukta.security.ApiRole;
import com.infenia.yukta.security.CallerIdentity;
import com.infenia.yukta.service.control.store.ExecutionControlStore;
import com.infenia.yukta.service.leader.LeaderElection;
import com.infenia.yukta.service.log.ExecutionLogEvent;
import com.infenia.yukta.service.log.InMemoryExecutionLogStore;
import com.infenia.yukta.service.redact.InputRedactor;
import com.infenia.yukta.service.supervisor.AttachedSupervisors;
import com.infenia.yukta.service.supervisor.BoundSupervisor;
import com.infenia.yukta.service.supervisor.ExecutionEventFeed;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

/**
 * A real broker with the real authoriser, resolver and guard around it, for tests that send
 * commands to the answer and escalate processors and read what happened.
 */
@SuppressWarnings({"PMD.ExcessiveImports", "PMD.CouplingBetweenObjects"})
public final class InputFlow {

  /** The store. */
  public final InMemoryInputRequestStore store = new InMemoryInputRequestStore(100);

  /** The execution log. */
  public final InMemoryExecutionLogStore log = new InMemoryExecutionLogStore();

  /** The meters. */
  public final SimpleMeterRegistry meters = new SimpleMeterRegistry();

  /** The feed. */
  public final ExecutionEventFeed feed = new ExecutionEventFeed();

  /** Whether this server leads. */
  public final LeaderElection leader = mock(LeaderElection.class);

  /** The supervisors attached at run time. */
  public final AttachedSupervisors attached = mock(AttachedSupervisors.class);

  /** The runs, for the separation rule. */
  public final ExecutionControlStore runs = mock(ExecutionControlStore.class);

  /** The broker. */
  public final InputBroker broker =
      new InputBroker(
          store,
          feed,
          log,
          meters,
          new InputRedactor(List.of()),
          InputTestSupport.limits(),
          new InputTestSupport.MutableClock());

  /** Decides who may answer. */
  public final AnswerAuthorizer authorizer = new DefaultAnswerAuthorizer(runs, meters);

  /** Works out who sends a command. */
  public final ActorResolver actors;

  /** Checks a command that addresses a request. */
  public final InputCommandGuard guard;

  /** Creates the flow; this server leads. */
  public InputFlow() {
    final ApiAuthenticator authenticator = mock(ApiAuthenticator.class);
    when(leader.isLeader()).thenReturn(true);
    this.actors = new ActorResolver(attached, authenticator);
    this.guard = new InputCommandGuard(leader, broker, actors, authorizer);
  }

  /**
   * Asks and keeps waiting; the answer lands in the returned reference.
   *
   * @param spec the question
   * @return the answer, once there is one
   */
  public AtomicReference<InputResponse> ask(final InputRequestSpec spec) {
    final AtomicReference<InputResponse> answer = new AtomicReference<>();
    broker.ask(InputTestSupport.who("exec", "node"), spec).subscribe(answer::set);
    return answer;
  }

  /**
   * The one open request.
   *
   * @return the request
   */
  public InputRequest onlyOpen() {
    return broker.listOpen(null, null).blockFirst();
  }

  /**
   * The first execution log entry of an input event.
   *
   * @param event the event, for example {@code INPUT_ANSWERED}
   * @return the entry
   */
  public ExecutionLogEvent logged(final String event) {
    return log.readAll().stream()
        .filter(
            entry -> {
              final Map<String, Object> payload = entry.payload();
              return payload != null && event.equals(payload.get("event"));
            })
        .findFirst()
        .orElseThrow();
  }

  /**
   * The request as it is now.
   *
   * @param id the request id
   * @return the request
   */
  public InputRequest current(final String id) {
    return broker.find(id).block();
  }

  /**
   * Attaches a supervisor that watches a session.
   *
   * @param id the binding id
   * @param canAnswer whether it may answer
   * @param max the highest risk it may answer
   */
  public void attach(final String id, final boolean canAnswer, final InputRisk max) {
    when(attached.find(id))
        .thenReturn(
            Optional.of(
                new BoundSupervisor(
                    new SupervisorBinding(id, "p", new SupervisorScope.Global(), true),
                    mock(ExecutionSupervisor.class),
                    false,
                    new SupervisorCapabilities(null, canAnswer, max))));
  }

  /**
   * The Reactor context of a call with an API key.
   *
   * @param name the key name
   * @param role the key's role
   * @param sessions the sessions the key is limited to
   * @return the context
   */
  public static Context asKey(final String name, final ApiRole role, final String... sessions) {
    return Context.of(
        ControlActor.CONTEXT_KEY,
        "key:" + name,
        ControlActor.KIND_KEY,
        ControlActor.Kind.KEY,
        CallerIdentity.CONTEXT_KEY,
        new ApiKeys.Match(name, role, Set.of(sessions)));
  }

  /**
   * The Reactor context of a supervisor's command.
   *
   * @param id the supervisor id
   * @return the context
   */
  public static Context asSupervisor(final String id) {
    return Context.of(
        ControlActor.CONTEXT_KEY, id, ControlActor.KIND_KEY, ControlActor.Kind.SUPERVISOR);
  }

  /**
   * Runs a mono under a context and waits for it.
   *
   * @param action what to run
   * @param context the context
   * @param <T> the result type
   * @return the result, or {@code null} when empty
   */
  public static <T> T under(final Mono<T> action, final Context context) {
    return action.contextWrite(context).block();
  }
}
