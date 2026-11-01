// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.input;

import com.infenia.yukta.plugin.control.ControlActor;
import com.infenia.yukta.plugin.control.SupervisorCapabilities;
import com.infenia.yukta.plugin.control.SupervisorScope;
import com.infenia.yukta.plugin.input.InputRisk;
import com.infenia.yukta.security.ApiAuthenticator;
import com.infenia.yukta.security.ApiKeys;
import com.infenia.yukta.security.ApiRole;
import com.infenia.yukta.security.CallerIdentity;
import com.infenia.yukta.service.supervisor.AttachedSupervisors;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.util.context.ContextView;

/**
 * Works out who sends a command from what the sender was authenticated as: the actor kind ({@link
 * ControlActor#KIND_KEY}) and, for a key, the resolved key ({@link CallerIdentity#CONTEXT_KEY}). It
 * never reads the shape of the actor string, because a supervisor id can look like a key name.
 *
 * <p>With neither a kind nor a key, the sender is a person when authentication is off, and has no
 * identity (and no rights) when it is on: the call did not come through a filter that checks keys.
 * A call that names an actor but no kind is not trusted either.
 */
@Component
public class ActorResolver {

  /** The name of a caller when no key is needed. */
  private static final String ANONYMOUS = "anonymous";

  /** Prefix of the actor of a one-time token caller. */
  private static final String TOKEN_PREFIX = "token:";

  /** What a supervisor without a profile may do: it can never answer. */
  private static final SupervisorCapabilities NO_ANSWERING =
      new SupervisorCapabilities(null, false, InputRisk.LOW);

  /** The supervisors attached at run time. */
  private final AttachedSupervisors attached;

  /** Tells whether authentication is required. */
  private final ApiAuthenticator authenticator;

  /**
   * Creates the resolver.
   *
   * @param attached the supervisors attached at run time
   * @param authenticator tells whether authentication is required
   */
  @SuppressFBWarnings("EI_EXPOSE_REP2") // shared Spring beans, held on purpose
  public ActorResolver(final AttachedSupervisors attached, final ApiAuthenticator authenticator) {
    this.attached = attached;
    this.authenticator = authenticator;
  }

  /**
   * The sender of the command being processed, from the Reactor context of the subscription.
   *
   * @return the sender
   */
  public Mono<ActorInfo> resolve() {
    return Mono.deferContextual(context -> Mono.just(resolve(context)));
  }

  /**
   * The sender described by a context.
   *
   * @param context the Reactor context of the command
   * @return the sender
   */
  public ActorInfo resolve(final ContextView context) {
    final String actor = context.getOrDefault(ControlActor.CONTEXT_KEY, null);
    final ControlActor.Kind kind = context.getOrDefault(ControlActor.KIND_KEY, null);
    final ApiKeys.Match match = context.getOrDefault(CallerIdentity.CONTEXT_KEY, null);
    final ActorInfo resolved;
    if (kind == ControlActor.Kind.SUPERVISOR && actor != null) {
      resolved = supervisor(actor);
    } else if (kind == ControlActor.Kind.TOKEN && actor != null) {
      resolved = ActorInfo.external(actor.startsWith(TOKEN_PREFIX) ? actor.substring(6) : actor);
    } else if (kind == ControlActor.Kind.KEY || kind == null && actor == null) {
      resolved = fromKey(match, kind == null);
    } else {
      resolved = ActorInfo.unauthenticated();
    }
    return resolved;
  }

  /** A caller with a key, or, with no key at all, a person only when authentication is off. */
  private ActorInfo fromKey(final ApiKeys.@Nullable Match match, final boolean unnamed) {
    final ActorInfo resolved;
    if (match != null) {
      resolved = ActorInfo.human(match.name(), match.role(), match.sessions());
    } else if (unnamed && isOpen()) {
      resolved = ActorInfo.human(ANONYMOUS, ApiRole.ADMIN, Set.of());
    } else {
      resolved = ActorInfo.unauthenticated();
    }
    return resolved;
  }

  private ActorInfo supervisor(final String id) {
    return attached
        .find(id)
        .map(bound -> ActorInfo.supervisor(id, bound.capabilities(), bound.scope()))
        .orElseGet(() -> ActorInfo.supervisor(id, NO_ANSWERING, new SupervisorScope.Global()));
  }

  private boolean isOpen() {
    return authenticator.isOpen(ApiAuthenticator.Scope.REST)
        && authenticator.isOpen(ApiAuthenticator.Scope.MCP);
  }
}
