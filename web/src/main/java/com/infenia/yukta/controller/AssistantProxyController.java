// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.controller;

import com.infenia.yukta.plugin.util.HostAllowList;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/**
 * Relays the experimental UI assistant's model calls to Anthropic or an OpenAI-compatible endpoint.
 *
 * <p>The browser's content security policy lets the page talk only to its own origin, so a
 * bring-your-own-key call goes through here. The relay is a pass-through: the request body goes to
 * the provider as it came, and the provider's status, content type and body (an event stream stays
 * a stream) come back as they are. The person's key arrives in {@code X-Assistant-Key}, is added to
 * the outgoing request and is neither logged nor stored. An OpenAI-compatible base URL (header
 * {@code X-Assistant-Base-Url}) must name a host in {@code yukta.assistant.allowed-base-urls}.
 */
@RestController
@RequestMapping("/api/assistant")
@Tag(name = "Assistant API", description = "Model relay for the experimental UI assistant")
public class AssistantProxyController {

  /** Header that carries the person's provider key. */
  public static final String KEY_HEADER = "X-Assistant-Key";

  /** Header that carries the OpenAI-compatible base URL. */
  public static final String BASE_URL_HEADER = "X-Assistant-Base-Url";

  /** Where an OpenAI-compatible call goes when the request names no base URL. */
  public static final String DEFAULT_OPENAI_BASE = "https://api.openai.com/v1";

  /** The hosts an OpenAI-compatible base URL may name unless the setting says otherwise. */
  public static final String DEFAULT_HOSTS =
      "api.openai.com,generativelanguage.googleapis.com,openrouter.ai";

  /** The largest request body relayed, in bytes. */
  public static final int MAX_BODY_BYTES = 1_048_576;

  /** The Anthropic API version header value. */
  private static final String ANTHROPIC_VERSION = "2023-06-01";

  /** The setting named in a refusal. */
  private static final String HOSTS_PROPERTY = "yukta.assistant.allowed-base-urls";

  /** The client that calls the provider; it carries the metadata-address guard. */
  private final WebClient client;

  /** Whether the relay is on. */
  private final boolean enabled;

  /** The hosts an OpenAI-compatible base URL may name. */
  private final HostAllowList allowedHosts;

  /** The Anthropic messages URL. */
  private final String anthropicUrl;

  /**
   * Creates the relay.
   *
   * @param builder builds the outgoing client
   * @param enabled {@code yukta.assistant.enabled}: the relay answers 404 when false
   * @param hosts {@code yukta.assistant.allowed-base-urls}: hosts an OpenAI-compatible base URL may
   *     name; none listed means none allowed
   * @param anthropicUrl {@code yukta.assistant.anthropic-url}: the Anthropic messages URL
   */
  public AssistantProxyController(
      final WebClient.Builder builder,
      @Value("${yukta.assistant.enabled:true}") final boolean enabled,
      @Value("${yukta.assistant.allowed-base-urls:" + DEFAULT_HOSTS + "}") final List<String> hosts,
      @Value("${yukta.assistant.anthropic-url:https://api.anthropic.com/v1/messages}")
          final String anthropicUrl) {
    this.client = builder.build();
    this.enabled = enabled;
    this.allowedHosts = HostAllowList.from(hosts);
    this.anthropicUrl = anthropicUrl;
  }

  /**
   * Relay one model call.
   *
   * @param provider {@code anthropic} or {@code openai}
   * @param request the incoming call, with the key header and the provider's request body
   * @param response written with the provider's reply
   * @return completes when the reply has been written
   */
  @PostMapping("/proxy/{provider}")
  @Operation(
      summary = "Relay an assistant model call",
      description =
          "Sends the body to Anthropic (provider anthropic) or an OpenAI-compatible endpoint"
              + " (provider openai) with the key from X-Assistant-Key and streams the reply back."
              + " An OpenAI-compatible base URL (X-Assistant-Base-Url) must name a host in"
              + " yukta.assistant.allowed-base-urls.")
  public Mono<Void> proxy(
      @Parameter(description = "anthropic or openai") @PathVariable final String provider,
      final ServerHttpRequest request,
      final ServerHttpResponse response) {
    if (!enabled) {
      return Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND));
    }
    final String key = request.getHeaders().getFirst(KEY_HEADER);
    if (key == null || key.isBlank()) {
      return Mono.error(
          new ResponseStatusException(HttpStatus.BAD_REQUEST, KEY_HEADER + " is required"));
    }
    final boolean anthropic = "anthropic".equals(provider);
    if (!anthropic && !"openai".equals(provider)) {
      return Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown provider"));
    }
    final String url =
        anthropic ? anthropicUrl : openAiUrl(request.getHeaders().getFirst(BASE_URL_HEADER));
    return DataBufferUtils.join(request.getBody(), MAX_BODY_BYTES)
        .map(AssistantProxyController::bytes)
        .defaultIfEmpty(new byte[0])
        .flatMap(body ->
            client
                .post()
                .uri(URI.create(url))
                .headers(h -> setAuth(h, anthropic, key))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchangeToMono(upstream -> {
                  response.setStatusCode(upstream.statusCode());
                  upstream.headers().contentType().ifPresent(response.getHeaders()::setContentType);
                  response.getHeaders().setCacheControl("no-cache");
                  return response.writeWith(upstream.bodyToFlux(DataBuffer.class));
                }));
  }

  private String openAiUrl(final String requested) {
    final String base =
        requested == null || requested.isBlank() ? DEFAULT_OPENAI_BASE : requested.strip();
    final String lower = base.toLowerCase(Locale.ROOT);
    if (!lower.startsWith("https://") && !lower.startsWith("http://")) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, BASE_URL_HEADER + " must be an http(s) URL");
    }
    if (!allowedHosts.names(base)) {
      throw new ResponseStatusException(
          HttpStatus.FORBIDDEN, "The base URL's host is not in " + HOSTS_PROPERTY);
    }
    return base.replaceAll("/+$", "") + "/chat/completions";
  }

  private static void setAuth(
      final HttpHeaders headers, final boolean anthropic, final String key) {
    if (anthropic) {
      headers.set("x-api-key", key.strip());
      headers.set("anthropic-version", ANTHROPIC_VERSION);
    } else {
      headers.setBearerAuth(key.strip());
    }
  }

  private static byte[] bytes(final DataBuffer buffer) {
    final byte[] out = new byte[buffer.readableByteCount()];
    buffer.read(out);
    DataBufferUtils.release(buffer);
    return out;
  }
}
