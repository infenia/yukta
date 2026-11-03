// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.input.delivery;

import com.infenia.yukta.plugin.input.InputRequest;
import com.infenia.yukta.plugin.input.InputResponder;
import com.infenia.yukta.plugin.util.EgressPolicy;
import com.infenia.yukta.service.input.token.AnswerTokens;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.spec.SecretKeySpec;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

/**
 * Delivers a request as JSON to a webhook ({@code external:webhook:<name>}). Targets are named
 * under {@code yukta.input.responders.<name>}: {@code url}, {@code headers} (values may use {@code
 * ${...}} placeholders for secrets), {@code timeout}, {@code signing-secret} and {@code
 * answer-url}. The URL must pass the egress policy and redirects are not followed.
 *
 * <p>The body holds the request as it is stored (prompt and context already redacted), the tier,
 * the deadline, and the one-time {@code token} with its expiry for answering at {@code answerUrl}.
 * It never holds an answer. With a {@code signing-secret} the header {@code X-Yukta-Signature:
 * sha256=<hmac of the body>} lets the receiver check the sender.
 *
 * @since 0.1.0-alpha.4
 */
@Component
public class WebhookInputResponder implements InputResponder {

  /** The responder type in a selector. */
  public static final String RESPONDER_TYPE = "webhook";

  /** The header that carries the signature. */
  public static final String SIGNATURE_HEADER = "X-Yukta-Signature";

  /** Prefix of the settings of the targets. */
  private static final String PREFIX = "yukta.input.responders.";

  /** How long a delivery may take when the target does not say. */
  private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);

  /** Posts. */
  private final WebClient client;

  /** Decides where the server may call. */
  private final EgressPolicy egress;

  /** The settings and their placeholders. */
  private final Environment environment;

  /** Writes the body. */
  private final JsonMapper json;

  /**
   * Creates the responder.
   *
   * @param builder builds the client
   * @param egress decides where the server may call
   * @param environment the settings
   * @param json writes the body
   */
  @SuppressFBWarnings("EI_EXPOSE_REP2") // shared Spring beans, held on purpose
  public WebhookInputResponder(
      final WebClient.Builder builder,
      final EgressPolicy egress,
      final Environment environment,
      final JsonMapper json) {
    this.client = builder.build();
    this.egress = egress;
    this.environment = environment;
    this.json = json;
  }

  @Override
  public String type() {
    return RESPONDER_TYPE;
  }

  @Override
  public Mono<Void> deliver(final InputRequest request, final Map<String, Object> tierSettings) {
    final Object name = tierSettings.get("name");
    final Target target =
        Binder.get(environment)
            .bind(PREFIX + name, Target.class)
            .orElseThrow(
                () ->
                    new IllegalStateException("No webhook target '" + name + "' under " + PREFIX));
    final byte[] body = json.writeValueAsBytes(payload(request, tierSettings, target));
    return egress
        .check(target.url())
        .then(
            Mono.defer(
                () ->
                    client
                        .post()
                        .uri(target.url())
                        .contentType(MediaType.APPLICATION_JSON)
                        .headers(
                            headers -> {
                              target
                                  .headers()
                                  .forEach(
                                      (key, value) ->
                                          headers.set(key, environment.resolvePlaceholders(value)));
                              final String secret = target.signingSecret();
                              if (secret != null) {
                                headers.set(SIGNATURE_HEADER, signature(secret, body));
                              }
                            })
                        .bodyValue(body)
                        .retrieve()
                        .toBodilessEntity()
                        .timeout(target.timeout() == null ? DEFAULT_TIMEOUT : target.timeout())
                        .then()));
  }

  @SuppressWarnings("PMD.UseConcurrentHashMap")
  /* default */ static Map<String, Object> payload(
      final InputRequest request, final Map<String, Object> settings, final Target target) {
    final Map<String, Object> body = new LinkedHashMap<>();
    body.put("requestId", request.id());
    body.put("executionId", request.executionId());
    body.put("sessionId", request.sessionId());
    body.put("workflowId", request.workflowId());
    body.put("kind", request.spec().kind());
    body.put("prompt", request.spec().prompt());
    body.put("context", request.spec().context());
    body.put("options", request.spec().options());
    body.put("risk", request.spec().risk());
    body.put("tier", request.tier());
    body.put("advice", request.advice());
    body.put("deadline", request.deadline());
    body.put("answerUrl", target.answerUrl());
    body.put("token", settings.get("answerToken"));
    body.put("tokenExpires", settings.get("answerExpires"));
    return body;
  }

  private static String signature(final String secret, final byte[] body) {
    final SecretKeySpec key =
        new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    return "sha256=" + HexFormat.of().formatHex(AnswerTokens.hmac("HmacSHA256", key, body));
  }

  /**
   * A webhook target.
   *
   * @param url where to post
   * @param headers extra headers; values may hold {@code ${...}} placeholders
   * @param timeout how long a post may take
   * @param signingSecret secret for the signature header, or {@code null} for none
   * @param answerUrl where the receiver answers with the token, or {@code null}
   */
  public record Target(
      String url,
      Map<String, String> headers,
      @Nullable Duration timeout,
      @Nullable String signingSecret,
      @Nullable String answerUrl) {

    /** Keeps a copy of the headers. */
    public Target {
      headers = headers == null ? Map.of() : Map.copyOf(headers);
    }
  }
}
