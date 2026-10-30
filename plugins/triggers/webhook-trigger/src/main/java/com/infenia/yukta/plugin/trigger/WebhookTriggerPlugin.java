// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.trigger;

import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.message.Message;
import com.infenia.yukta.plugin.core.ConfigField;
import com.infenia.yukta.plugin.core.PluginDescriptor;
import com.infenia.yukta.plugin.core.UiDesign;
import com.infenia.yukta.plugin.type.TriggerPlugin;
import com.infenia.yukta.plugin.util.ConfigValidation;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Starts a workflow from an inbound HTTP call to {@code POST /hooks/{sessionId}/{path}}. The web
 * module checks the signature and starts the run; this node emits the payload it was started with
 * ({@code body}, the raw request body, and {@code headers}, the ones named in {@code headers}).
 */
@Component
public class WebhookTriggerPlugin implements TriggerPlugin {

  /** Default constructor. */
  public WebhookTriggerPlugin() {
    super();
  }

  @Override
  public PluginDescriptor describe() {
    return new PluginDescriptor(
        "Starts the workflow from a signed inbound webhook call.",
        "POST /hooks/{sessionId}/{path} with the body signed by the secret. The call is refused"
            + " (401) unless the signature header holds the hex HMAC of the raw body. Config:"
            + " path, secret (decrypted:name), signatureHeader (default X-Hub-Signature-256),"
            + " signaturePrefix (default sha256=), idempotencyHeader (such as X-GitHub-Delivery),"
            + " headers (comma-separated names to pass on). Payload: {body, headers}.",
        Optional.of(new UiDesign("blue", "webhook", "Webhook", 140, 80)));
  }

  @Override
  public String getType() {
    return "WEBHOOK_TRIGGER";
  }

  @Override
  public List<ConfigField> getConfigSchema() {
    return List.of(
        ConfigField.required("path", ConfigField.Type.STRING, "Last part of the hook URL"),
        ConfigField.required(
            "secret", ConfigField.Type.STRING, "Signing secret, a decrypted:<name> reference"),
        ConfigField.optional(
            "signatureHeader",
            ConfigField.Type.STRING,
            "X-Hub-Signature-256",
            "Header that holds the signature"),
        ConfigField.optional(
            "signaturePrefix", ConfigField.Type.STRING, "sha256=", "Text before the hex digest"),
        ConfigField.optional(
            "idempotencyHeader",
            ConfigField.Type.STRING,
            null,
            "Header whose value makes a repeated delivery return the first run"),
        ConfigField.optional(
            "headers", ConfigField.Type.STRING, null, "Comma-separated headers passed in payload"));
  }

  @Override
  public Mono<Void> validateConfig(final Map<String, Object> config) {
    return Mono.fromRunnable(
        () -> {
          ConfigValidation.requiredString(config, "path");
          ConfigValidation.requiredString(config, "secret");
        });
  }

  @Override
  public Flux<Message<?>> start(final Map<String, Object> config) {
    return Flux.deferContextual(
        ctx -> {
          final Map<String, Object> payload = ctx.getOrDefault("payload", Map.of());
          return Flux.just(
              DefaultMessage.create(UUID.randomUUID(), payload).withSourcePort("default"));
        });
  }
}
