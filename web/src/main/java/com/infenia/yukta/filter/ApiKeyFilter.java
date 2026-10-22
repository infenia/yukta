// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.filter;

import com.infenia.yukta.model.execution.WorkflowProgress;
import com.infenia.yukta.plugin.control.ControlActor;
import com.infenia.yukta.security.ApiAuthenticator;
import com.infenia.yukta.security.ApiAuthenticator.Scope;
import com.infenia.yukta.security.ApiKeyAuthenticator;
import com.infenia.yukta.security.ApiKeys;
import com.infenia.yukta.security.ApiRole;
import com.infenia.yukta.security.RequestPaths;
import com.infenia.yukta.service.control.gateway.ControlBusGateway;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Requires an API key on every {@code /api/**} request, on the OpenAPI document and Swagger UI and
 * on the actuator (except {@code /actuator/health}) when one is configured, as {@code
 * Authorization: Bearer <key>} or {@code X-API-Key: <key>}. With no key configured the API stays
 * open, which is safe only because the server listens on loopback by default (and refuses to start
 * on another address without a key).
 *
 * <p>The path is matched after percent-decoding; a path written with parameters, encoded separators
 * or dot segments is refused with 400, so no spelling of a path skips the check.
 *
 * <p>{@code yukta.api-key} is a single key that may do everything. {@code yukta.api-keys} lists
 * named keys with a role (see {@link ApiRole}) and optionally the sessions the key is limited to; a
 * key is plain text or {@code sha256:<hex>}:
 *
 * <pre>
 * yukta.api-keys[0].name=alice
 * yukta.api-keys[0].key=...
 * yukta.api-keys[0].role=OPERATOR
 * yukta.api-keys[0].sessions=team-a,team-b
 * </pre>
 *
 * <p>A key whose role or sessions do not allow the request gets 403. A session-limited key reaches
 * {@code /api/sessions/{session}/**}, {@code /api/workflow/{session}/**} and the {@code
 * /api/executions/{id}/**} of runs in its sessions. With named keys, the control commands a request
 * sends are recorded with the actor {@code key:<name>}.
 */
@Component
@NullMarked
@Order(Ordered.HIGHEST_PRECEDENCE)
@SuppressWarnings({"PMD.LawOfDemeter", "PMD.OnlyOneReturn"})
public class ApiKeyFilter implements WebFilter {

  /** The one actuator endpoint that needs no key (liveness probes). */
  private static final String HEALTH = "/actuator/health";

  /** The execution of an execution-scoped API path. */
  private static final Pattern EXECUTION_PATH =
      Pattern.compile("^/api/executions/([^/]+)(?:/.*)?$");

  /** The session of a session-scoped API path. */
  private static final Pattern SESSION_PATH =
      Pattern.compile("^/api/(?:sessions|workflow)/([^/]+)(?:/.*)?$");

  /** Identifies the caller; an open authenticator disables the check. */
  private final ApiAuthenticator authenticator;

  /** Finds the session of an execution, or null if unknown. */
  @Nullable private final ControlBusGateway controlBus;

  /**
   * Create the filter from the configuration.
   *
   * @param environment holds {@code yukta.api-key} and {@code yukta.api-keys}
   */
  public ApiKeyFilter(final Environment environment) {
    this(ApiKeyAuthenticator.read(environment), null);
  }

  /**
   * Create the filter from the configuration, with the API keys as authenticator.
   *
   * @param environment holds {@code yukta.api-key} and {@code yukta.api-keys}
   * @param controlBus finds the session of an execution, or null
   */
  public ApiKeyFilter(final Environment environment, @Nullable final ControlBusGateway controlBus) {
    this(ApiKeyAuthenticator.read(environment), controlBus);
  }

  /**
   * Create the filter from the configuration, able to tell which session an execution belongs to.
   *
   * @param authenticator identifies the caller (API keys by default)
   * @param controlBus finds the session of an execution; without it a session-limited key cannot
   *     reach {@code /api/executions/{id}/**}
   */
  @Autowired
  @SuppressFBWarnings("EI_EXPOSE_REP2") // both are shared Spring beans
  public ApiKeyFilter(
      final ApiAuthenticator authenticator, @Nullable final ControlBusGateway controlBus) {
    this.authenticator = authenticator;
    this.controlBus = controlBus;
  }

  /**
   * Create the filter with one key that may do everything.
   *
   * @param apiKey the required key, or empty to leave the API open
   */
  public ApiKeyFilter(final String apiKey) {
    this.authenticator = ApiKeyAuthenticator.single(apiKey);
    this.controlBus = null;
  }

  @Override
  public Mono<Void> filter(final ServerWebExchange exchange, final WebFilterChain chain) {
    final String raw = exchange.getRequest().getPath().value();
    if (authenticator.isOpen(Scope.REST)) {
      return chain.filter(exchange);
    }
    if (RequestPaths.ambiguous(raw)) {
      return reject(exchange, HttpStatus.BAD_REQUEST);
    }
    final String path = RequestPaths.decoded(raw);
    if (!guarded(path)) {
      return chain.filter(exchange);
    }
    final ApiKeys.Match match =
        authenticator.authenticate(Scope.REST, exchange.getRequest().getHeaders());
    if (match == null) {
      exchange.getResponse().getHeaders().set(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
      return reject(exchange, HttpStatus.UNAUTHORIZED);
    }
    if (!permitted(match, exchange, path)) {
      return reject(exchange, HttpStatus.FORBIDDEN);
    }
    return authenticator.named()
        ? chain
            .filter(exchange)
            .contextWrite(ctx -> ctx.put(ControlActor.CONTEXT_KEY, "key:" + match.name()))
        : chain.filter(exchange);
  }

  /** The API, the actuator (except the health probe) and the API description. */
  private static boolean guarded(final String path) {
    return isUnder(path, "/api")
        || (isUnder(path, "/actuator") && !isUnder(path, HEALTH))
        || isDocs(path);
  }

  /** The OpenAPI document and the Swagger UI: they describe every endpoint. */
  private static boolean isDocs(final String path) {
    return isUnder(path, "/v3") || isUnder(path, "/swagger-ui") || "/swagger-ui.html".equals(path);
  }

  private static boolean isUnder(final String path, final String prefix) {
    return prefix.equals(path) || path.startsWith(prefix + "/");
  }

  private boolean permitted(
      final ApiKeys.Match match, final ServerWebExchange exchange, final String path) {
    if (isDocs(path)) {
      return HttpMethod.GET.equals(exchange.getRequest().getMethod());
    }
    if (isUnder(path, "/actuator")) {
      return match.role() == ApiRole.ADMIN && match.sessions().isEmpty();
    }
    if (!match.role().allows(exchange.getRequest().getMethod(), path)) {
      return false;
    }
    if (match.sessions().isEmpty()) {
      return true;
    }
    final Matcher session = SESSION_PATH.matcher(path);
    if (session.matches()) {
      return match.reaches(session.group(1));
    }
    final Matcher execution = EXECUTION_PATH.matcher(path);
    return execution.matches() && ownsExecution(match, execution.group(1));
  }

  /** A session-limited key may control only the executions of its sessions. */
  private boolean ownsExecution(final ApiKeys.Match match, final String executionId) {
    final WorkflowProgress progress =
        controlBus == null ? null : controlBus.getCurrentProgress(executionId);
    return progress != null && match.reaches(progress.sessionId());
  }

  private static Mono<Void> reject(final ServerWebExchange exchange, final HttpStatus status) {
    exchange.getResponse().setStatusCode(status);
    return exchange.getResponse().setComplete();
  }
}
