// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.input.route;

import com.infenia.yukta.config.YuktaProperties;
import com.infenia.yukta.model.session.SessionSnapshot;
import com.infenia.yukta.plugin.input.InputRequestSpec;
import com.infenia.yukta.service.session.store.SessionConfigStore;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Picks the route of a request: the one its spec names, then the one the session names in its
 * {@code input.route} tag, then {@code yukta.input.routes.default}, then the built-in route of one
 * tier that any supervisor or operator may answer, with no timeout.
 *
 * @since 0.1.0-alpha.4
 */
@Component
public class RouteResolver {

  /** The session tag that names a route. */
  public static final String SESSION_TAG = "input.route";

  /** The name of the route used when nothing else is named. */
  public static final String DEFAULT_ROUTE = "default";

  /** The built-in route: one tier for any supervisor or operator, no timeout. */
  public static final InputRoute BUILT_IN =
      new InputRoute(
          DEFAULT_ROUTE,
          List.of(
              new Tier(
                  "default",
                  List.of("supervisor:*", "role:OPERATOR"),
                  null,
                  Mode.FIRST,
                  0,
                  0,
                  ResponderType.HUMAN,
                  null)),
          FinalAction.FAIL);

  /** The routes of the application, by name. */
  private final Map<String, InputRoute> routes;

  /** The session settings, when the application keeps sessions. */
  private final @Nullable ObjectProvider<SessionConfigStore> sessions;

  /**
   * Creates the resolver.
   *
   * @param properties the {@code yukta.input.routes} settings
   * @param sessions the session settings, to read the {@code input.route} tag
   */
  @Autowired
  public RouteResolver(
      final YuktaProperties properties, final ObjectProvider<SessionConfigStore> sessions) {
    this(named(properties.input().routes()), sessions);
  }

  private static Map<String, InputRoute> named(final Map<String, RouteSettings> settings) {
    final Map<String, InputRoute> routes = new LinkedHashMap<>();
    settings.forEach((name, route) -> routes.put(name, route.named(name)));
    return routes;
  }

  /**
   * Creates the resolver over given routes.
   *
   * @param routes the routes by name
   * @param sessions the session settings, or {@code null} for none
   */
  public RouteResolver(
      final Map<String, InputRoute> routes,
      final @Nullable ObjectProvider<SessionConfigStore> sessions) {
    this.routes = Map.copyOf(routes);
    this.sessions = sessions;
  }

  /**
   * Finds the route of a request.
   *
   * @param spec the question, which may name a route
   * @param sessionId the session of the run that asks
   * @return the route; never empty
   */
  public Mono<InputRoute> resolve(final InputRequestSpec spec, final String sessionId) {
    final InputRoute named = spec.routeName() == null ? null : routes.get(spec.routeName());
    if (spec.routeName() != null && named == null) {
      return Mono.error(
          new IllegalArgumentException("No input route is called '" + spec.routeName() + "'"));
    }
    if (named != null) {
      return Mono.just(named);
    }
    return sessionRoute(sessionId)
        .map(name -> routes.getOrDefault(name, fallback()))
        .defaultIfEmpty(fallback());
  }

  /**
   * Whether a route of this name is configured.
   *
   * @param name the route name
   * @return true if {@code yukta.input.routes} holds it
   */
  public boolean knows(final String name) {
    return routes.containsKey(name);
  }

  private InputRoute fallback() {
    return routes.getOrDefault(DEFAULT_ROUTE, BUILT_IN);
  }

  private Mono<String> sessionRoute(final String sessionId) {
    final SessionConfigStore store = sessions == null ? null : sessions.getIfAvailable();
    if (store == null) {
      return Mono.empty();
    }
    return store
        .getAllConfigs(sessionId)
        .mapNotNull((SessionSnapshot snapshot) -> snapshot.tags().get(SESSION_TAG))
        .onErrorResume(error -> Mono.empty());
  }
}
