// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp;

import com.infenia.yukta.plugin.control.ControlActor;
import com.infenia.yukta.security.ApiAuthenticator;
import com.infenia.yukta.security.ApiAuthenticator.Scope;
import com.infenia.yukta.security.ApiKeyAuthenticator;
import com.infenia.yukta.security.ApiKeys;
import com.infenia.yukta.security.ApiRole;
import com.infenia.yukta.security.CallRateLimiter;
import com.infenia.yukta.security.CallerIdentity;
import com.infenia.yukta.security.RequestPaths;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.util.concurrent.atomic.LongAdder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Guards the MCP endpoint ({@value #ENDPOINT}). When a key is configured, requests must carry it as
 * {@code Authorization: Bearer <key>} or {@code X-API-Key: <key>}. The keys are the REST keys
 * ({@code yukta.api-key}, {@code yukta.api-keys}) plus the optional {@code yukta.mcp.api-key}, so
 * setting only the REST key closes MCP too. A key that is not an unrestricted {@code ADMIN} has the
 * JSON-RPC body read: a {@code tools/call} needs the role {@link McpToolAccess} lists for the tool
 * and, for a session-limited key, a {@code sessionId} of its own (403 otherwise). The path is
 * matched after decoding, and a path with parameters, encoded separators or dot segments is refused
 * with 400. Each caller (key) may make {@code yukta.mcp.rate-limit-per-minute} requests per minute
 * (default 600, 0 = unlimited); more get 429 with {@code Retry-After}. {@code start_workflow} and
 * {@code get_execution_logs} with a {@code filterPattern} (a regex run over every line) also count
 * against {@code yukta.mcp.heavy-rate-limit-per-minute} (default 60, 0 = unlimited), refused calls
 * are counted in {@code yukta.mcp.heavy.refused}. An endpoint with no key (loopback only) is not
 * limited. Also keeps the former {@value #LEGACY_ENDPOINT} path working by rewriting it to the
 * endpoint.
 */
@Component
@SuppressWarnings({"PMD.TooManyMethods", "PMD.ExcessiveImports"})
@Order(Ordered.HIGHEST_PRECEDENCE)
public class McpEndpointFilter implements WebFilter, MeterBinder {

  /** Path of the streamable-HTTP MCP endpoint. */
  private static final String ENDPOINT = "/mcp";

  /** Largest request body read to check the tools it calls. */
  private static final int MAX_BODY_BYTES = 1 << 20;

  /** Former endpoint name, kept as an alias for clients that are already configured. */
  private static final String LEGACY_ENDPOINT = "/sse";

  /** Requests per caller and minute when {@code yukta.mcp.rate-limit-per-minute} is not set. */
  private static final int DEFAULT_RATE_LIMIT = 600;

  /**
   * Costly calls per caller and minute when {@code yukta.mcp.heavy-rate-limit-per-minute} is unset.
   */
  private static final int DEFAULT_HEAVY_RATE_LIMIT = 60;

  /** Identifies the caller; an open authenticator disables the check. */
  private final ApiAuthenticator authenticator;

  /** Caps the requests of one caller. */
  private final CallRateLimiter limiter;

  /** Caps the costly tool calls (start_workflow, regex log reads) of one caller. */
  private final CallRateLimiter heavyLimiter;

  /** Costly calls refused so far. */
  private final LongAdder heavyRefused = new LongAdder();

  /**
   * Create the filter from the configuration.
   *
   * @param environment holds the REST keys and {@code yukta.mcp.api-key}
   */
  public McpEndpointFilter(final Environment environment) {
    this(ApiKeyAuthenticator.read(environment), environment);
  }

  /**
   * Create the filter.
   *
   * @param authenticator identifies the caller (API keys by default)
   * @param environment holds {@code yukta.mcp.rate-limit-per-minute}
   */
  @Autowired
  public McpEndpointFilter(final ApiAuthenticator authenticator, final Environment environment) {
    this(
        authenticator,
        new CallRateLimiter(
            environment.getProperty(
                "yukta.mcp.rate-limit-per-minute", Integer.class, DEFAULT_RATE_LIMIT)),
        new CallRateLimiter(
            environment.getProperty(
                "yukta.mcp.heavy-rate-limit-per-minute", Integer.class, DEFAULT_HEAVY_RATE_LIMIT)));
  }

  /**
   * Create the filter.
   *
   * @param authenticator identifies the caller (API keys by default)
   */
  public McpEndpointFilter(final ApiAuthenticator authenticator) {
    this(authenticator, new CallRateLimiter(DEFAULT_RATE_LIMIT));
  }

  /**
   * Create the filter.
   *
   * @param authenticator identifies the caller (API keys by default)
   * @param limiter caps the requests of one caller
   */
  public McpEndpointFilter(final ApiAuthenticator authenticator, final CallRateLimiter limiter) {
    this(authenticator, limiter, new CallRateLimiter(DEFAULT_HEAVY_RATE_LIMIT));
  }

  /**
   * Create the filter.
   *
   * @param authenticator identifies the caller (API keys by default)
   * @param limiter caps the requests of one caller
   * @param heavyLimiter caps the costly tool calls of one caller
   */
  public McpEndpointFilter(
      final ApiAuthenticator authenticator,
      final CallRateLimiter limiter,
      final CallRateLimiter heavyLimiter) {
    this.authenticator = authenticator;
    this.limiter = limiter;
    this.heavyLimiter = heavyLimiter;
  }

  /**
   * Create the filter with one key that may do everything.
   *
   * @param apiKey the required key, or empty to leave the endpoint open
   */
  public McpEndpointFilter(final String apiKey) {
    this.authenticator = ApiKeyAuthenticator.single(apiKey);
    this.limiter = new CallRateLimiter(DEFAULT_RATE_LIMIT);
    this.heavyLimiter = new CallRateLimiter(DEFAULT_HEAVY_RATE_LIMIT);
  }

  @Override
  public void bindTo(final MeterRegistry registry) {
    FunctionCounter.builder("yukta.mcp.heavy.refused", heavyRefused, LongAdder::doubleValue)
        .description(
            "MCP tool calls refused by the lower limit for start_workflow and regex log reads")
        .register(registry);
  }

  @Override
  public Mono<Void> filter(final ServerWebExchange exchange, final WebFilterChain chain) {
    final String raw = exchange.getRequest().getPath().value();
    final String path = RequestPaths.normalized(raw);
    final boolean legacy = LEGACY_ENDPOINT.equals(path);
    if (!legacy && !ENDPOINT.equals(path)) {
      return chain.filter(exchange);
    }
    if (authenticator.isOpen(Scope.MCP)) {
      // no key means loopback only (ExposureGuard) and no caller to tell apart, like REST: no limit
      return chain.filter(rewritten(exchange, legacy));
    }
    final ApiKeys.Match match =
        authenticator.authenticate(Scope.MCP, exchange.getRequest().getHeaders());
    final HttpStatus refusal = refusal(raw, match);
    if (refusal != null) {
      return reject(exchange, refusal);
    }
    return limited(match.name()) ? tooMany(exchange) : proceed(exchange, chain, match, legacy);
  }

  /** Hands an authenticated, within-limit request on, naming the actor for a named key. */
  private Mono<Void> proceed(
      final ServerWebExchange exchange,
      final WebFilterChain chain,
      final ApiKeys.Match match,
      final boolean legacy) {
    final Mono<Void> next =
        checked(exchange, chain, match, legacy)
            .contextWrite(ctx -> ctx.put(CallerIdentity.CONTEXT_KEY, match));
    return authenticator.named()
        ? next.contextWrite(ctx -> ctx.put(ControlActor.CONTEXT_KEY, "key:" + match.name()))
        : next;
  }

  /**
   * Reads the JSON-RPC body, refuses a tool call the key may not make with 403 (an unrestricted
   * ADMIN may make any) and a caller over the lower limit for costly calls with 429, and hands the
   * same body on.
   */
  private Mono<Void> checked(
      final ServerWebExchange exchange,
      final WebFilterChain chain,
      final ApiKeys.Match match,
      final boolean legacy) {
    if (!HttpMethod.POST.equals(exchange.getRequest().getMethod())) {
      return chain.filter(rewritten(exchange, legacy));
    }
    return DataBufferUtils.join(exchange.getRequest().getBody(), MAX_BODY_BYTES)
        .map(
            buffer -> {
              final byte[] bytes = new byte[buffer.readableByteCount()];
              buffer.read(bytes);
              DataBufferUtils.release(buffer);
              return bytes;
            })
        .defaultIfEmpty(new byte[0])
        .flatMap(
            bytes -> {
              final boolean unrestricted =
                  match.role() == ApiRole.ADMIN && match.sessions().isEmpty();
              if (bytes.length > 0 && !unrestricted && !McpToolAccess.permitted(match, bytes)) {
                return reject(exchange, HttpStatus.FORBIDDEN);
              }
              if (heavyOverLimit(match.name(), bytes)) {
                return tooMany(exchange);
              }
              return chain.filter(rewritten(withBody(exchange, bytes), legacy));
            })
        .onErrorResume(
            DataBufferLimitException.class, _ -> reject(exchange, HttpStatus.CONTENT_TOO_LARGE));
  }

  /** Whether the costly calls in the body put this caller over its lower limit (counts them). */
  private boolean heavyOverLimit(final String caller, final byte[] body) {
    final int heavy = body.length == 0 ? 0 : McpToolAccess.heavyCalls(body);
    for (int i = 0; i < heavy; i++) {
      if (!heavyLimiter.tryAcquire(caller)) {
        heavyRefused.increment();
        return true;
      }
    }
    return false;
  }

  /** Whether this caller is over its request limit (counts the request). */
  private boolean limited(final String caller) {
    return !limiter.tryAcquire(caller);
  }

  private static Mono<Void> tooMany(final ServerWebExchange exchange) {
    exchange.getResponse().getHeaders().set("Retry-After", "60");
    return reject(exchange, HttpStatus.TOO_MANY_REQUESTS);
  }

  private static ServerWebExchange withBody(final ServerWebExchange exchange, final byte[] bytes) {
    final ServerHttpRequest request =
        new ServerHttpRequestDecorator(exchange.getRequest()) {
          @Override
          public Flux<DataBuffer> getBody() {
            return Flux.just(exchange.getResponse().bufferFactory().wrap(bytes));
          }
        };
    return exchange.mutate().request(request).build();
  }

  /** Why a request to the endpoint is refused before its body is read, or null. */
  private static HttpStatus refusal(final String raw, final ApiKeys.Match match) {
    if (RequestPaths.ambiguous(raw)) {
      return HttpStatus.BAD_REQUEST;
    }
    if (match == null) {
      return HttpStatus.UNAUTHORIZED;
    }
    return null;
  }

  private static ServerWebExchange rewritten(
      final ServerWebExchange exchange, final boolean legacy) {
    return legacy ? exchange.mutate().request(request -> request.path(ENDPOINT)).build() : exchange;
  }

  private static Mono<Void> reject(final ServerWebExchange exchange, final HttpStatus status) {
    exchange.getResponse().setStatusCode(status);
    return exchange.getResponse().setComplete();
  }
}
