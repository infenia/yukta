// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.notify;

import com.infenia.yukta.plugin.input.InputRequest;
import com.infenia.yukta.plugin.notify.ChannelContext;
import com.infenia.yukta.plugin.notify.Notification;
import com.infenia.yukta.plugin.notify.NotificationChannel;
import com.infenia.yukta.plugin.util.EgressPolicy;
import com.infenia.yukta.service.input.token.AnswerTokens;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.spec.SecretKeySpec;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

/**
 * Delivers a notification as JSON to a webhook ({@code webhook:<name>} in a rule, {@code
 * external:webhook:<name>} in a route tier). Targets are named under {@code
 * yukta.notifications.channels.webhook.<name>}: {@code url}, {@code headers} (values may use {@code
 * ${...}} placeholders for secrets), {@code timeout}, {@code signing-secret} and {@code
 * answer-url}. The URL must pass the egress policy and redirects are not followed.
 *
 * <p>The body is additive. An action keeps the fields receivers of {@code alpha.4} read ({@code
 * requestId}, {@code prompt}, {@code context}, {@code token}, ...: the request as it is stored,
 * prompt and context already redacted, the tier, the deadline and, for a tier delivery, the
 * one-time {@code token} with its expiry for answering at {@code answerUrl}) and gains a {@code
 * notification} object. An info notification carries only {@code notification}. It never holds an
 * answer. With a {@code signing-secret} the header {@code X-Yukta-Signature: sha256=<hmac of the
 * body>} lets the receiver check the sender.
 *
 * @since 0.1.0-alpha.5
 */
@Component
@SuppressWarnings("PMD.UseConcurrentHashMap")
public class WebhookChannel implements NotificationChannel {

  /** The channel type in a selector or a rule. */
  public static final String CHANNEL_TYPE = "webhook";

  /** The header that carries the signature. */
  public static final String SIGNATURE_HEADER = "X-Yukta-Signature";

  /** How long a delivery may take when the target does not say. */
  private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);

  /** Posts. */
  private final WebClient client;

  /** Decides where the server may call. */
  private final EgressPolicy egress;

  /** Resolves the placeholders of header values. */
  private final Environment environment;

  /** Writes the body. */
  private final JsonMapper json;

  /**
   * Creates the channel.
   *
   * @param builder builds the client
   * @param egress decides where the server may call
   * @param environment resolves placeholders
   * @param json writes the body
   */
  public WebhookChannel(
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
    return CHANNEL_TYPE;
  }

  @Override
  public Mono<Void> deliver(final Notification notification, final ChannelContext context) {
    final Target target = target(context);
    final byte[] body = json.writeValueAsBytes(payload(notification, context, target));
    return egress
        .check(target.url())
        .then(
            Mono.defer(() ->
                client
                    .post()
                    .uri(target.url())
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(headers -> {
                      target
                          .headers()
                          .forEach((key, value) ->
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

  /** Reads the target from the settings of the channel, with the usual relaxed names. */
  private static Target target(final ChannelContext context) {
    final Map<String, Object> flat = new LinkedHashMap<>();
    flatten("", context.settings(), flat);
    return new Binder(new MapConfigurationPropertySource(flat))
        .bind(ConfigurationPropertyName.EMPTY, Bindable.of(Target.class))
        .orElseThrow(() ->
            new IllegalStateException(
                "No webhook target '"
                    + context.name()
                    + "' under yukta.notifications.channels.webhook"));
  }

  private static void flatten(
      final String prefix, final Map<String, Object> from, final Map<String, Object> into) {
    from.forEach((key, value) -> {
      final String name = prefix + key;
      if (value instanceof Map<?, ?> nested) {
        final Map<String, Object> inner = new LinkedHashMap<>();
        nested.forEach((k, v) -> inner.put(String.valueOf(k), v));
        flatten(name + ".", inner, into);
      } else {
        into.put(name, value);
      }
    });
  }

  /* default */ static Map<String, Object> payload(
      final Notification notification, final ChannelContext context, final Target target) {
    final Map<String, Object> body = new LinkedHashMap<>();
    final InputRequest request = context.request();
    if (request != null) {
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
      body.put("token", context.answerToken());
      final Instant expires = context.answerExpires();
      body.put("tokenExpires", expires == null ? null : expires.getEpochSecond());
    }
    body.put("notification", summary(notification));
    return body;
  }

  /** The notification as a receiver sees it: never who it was for. */
  private static Map<String, Object> summary(final Notification n) {
    final Map<String, Object> out = new LinkedHashMap<>();
    out.put("id", n.id());
    out.put("key", n.key());
    out.put("kind", n.kind());
    out.put("severity", n.severity());
    out.put("category", n.category());
    out.put("title", n.title());
    out.put("body", n.body());
    out.put("sessionId", n.sessionId());
    out.put("workflowId", n.workflowId());
    out.put("executionId", n.executionId());
    out.put("nodeId", n.nodeId());
    out.put("inputRequestId", n.inputRequestId());
    out.put("state", n.state());
    out.put("createdAt", n.createdAt());
    return out;
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
