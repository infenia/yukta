// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.supervisor.webhook;

import com.infenia.yukta.plugin.control.ExecutionSupervisor;
import com.infenia.yukta.plugin.control.Supervisor;
import com.infenia.yukta.plugin.control.SupervisorCapabilities;
import com.infenia.yukta.plugin.control.SupervisorCategory;
import com.infenia.yukta.plugin.control.SupervisorDescriptor;
import com.infenia.yukta.plugin.control.SupervisorFactory;
import com.infenia.yukta.plugin.core.ConfigField;
import com.infenia.yukta.plugin.core.ConfigField.Type;
import com.infenia.yukta.plugin.core.UiDesign;
import com.infenia.yukta.plugin.util.EgressPolicy;
import com.infenia.yukta.plugin.util.HostAllowList;
import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.UnaryOperator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Makes {@link WebhookSupervisor}s from supervisors. */
@Component
@SuppressWarnings({
  "PMD.AvoidFieldNameMatchingMethodName", // TYPE and type()
  "PMD.UseConcurrentHashMap", // header maps keep their order
  "PMD.AvoidDuplicateLiterals"
})
public class WebhookSupervisorFactory implements SupervisorFactory {

  /** The supervisor type. */
  public static final String TYPE = "callout";

  /** Longest timeout, in milliseconds. */
  public static final int MAX_TIMEOUT_MS = 10_000;

  private static final String URL = "url";

  private static final String PREFIX = "${env.";

  private static final List<String> DEFAULT_EVENTS = List.of("InputRequested", "NodeStatusChanged");

  private final Egress egress;

  private final UnaryOperator<String> environment;

  /**
   * Creates the factory.
   *
   * @param policy the deployment's host lists
   * @param hosts {@code yukta.http.allowed-hosts}
   */
  @Autowired
  public WebhookSupervisorFactory(
      final EgressPolicy policy, @Value("${yukta.http.allowed-hosts:}") final List<String> hosts) {
    this(new Egress(policy, HostAllowList.from(hosts)), System::getenv);
  }

  /**
   * Creates the factory with its parts.
   *
   * @param egress decides which URLs may be called
   * @param environment looks up an environment variable, or returns null
   */
  public WebhookSupervisorFactory(final Egress egress, final UnaryOperator<String> environment) {
    this.egress = egress;
    this.environment = environment;
  }

  @Override
  public String type() {
    return TYPE;
  }

  @Override
  public ExecutionSupervisor create(final Supervisor supervisor) {
    final SupervisorCapabilities capabilities = SupervisorCapabilities.from(supervisor, Set.of());
    return new WebhookSupervisor(
        supervisor.id(),
        config(supervisor.settings()),
        Objects.requireNonNullElse(capabilities.allowedCommands(), Set.of()),
        egress);
  }

  @Override
  public Set<String> defaultAllowedCommands() {
    return Set.of();
  }

  /* default */ WebhookSupervisor.Config config(final Map<String, Object> settings) {
    final String url = Settings.requiredText(settings, URL);
    final URI uri = URI.create(url);
    if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
        || uri.getHost() == null) {
      throw new IllegalArgumentException("url must be an http or https URL with a host");
    }
    final int timeout = Settings.bounded(settings, "timeoutMs", 3000, MAX_TIMEOUT_MS);
    final Map<String, String> headers = new LinkedHashMap<>();
    headers.putAll(headers(settings.get("headers"), "headers"));
    headers.putAll(headers(settings.get("secretHeaders"), "secretHeaders"));
    return new WebhookSupervisor.Config(
        url,
        headers,
        events(settings.get("events")),
        Duration.ofMillis(timeout),
        Settings.flag(settings.get("review")),
        reviewOnError(settings.get("reviewOnError")),
        Settings.bounded(settings, "maxConcurrent", 4, 32));
  }

  private Map<String, String> headers(final Object value, final String key) {
    if (value == null) {
      return Map.of();
    }
    if (!(value instanceof final Map<?, ?> map)) {
      throw new IllegalArgumentException(key + " must be an object of header names and values");
    }
    final Map<String, String> out = new LinkedHashMap<>();
    map.forEach(
        (name, text) -> out.put(String.valueOf(name), resolve(key, String.valueOf(name), text)));
    return out;
  }

  /** A header value; ${env.NAME} is read from the environment. The message holds no value. */
  private String resolve(final String key, final String name, final Object text) {
    final String raw = String.valueOf(text);
    if (raw.startsWith(PREFIX) && raw.endsWith("}")) {
      final String variable = raw.substring(PREFIX.length(), raw.length() - 1);
      final String found = environment.apply(variable);
      if (found == null) {
        throw new IllegalArgumentException(
            key
                + "."
                + name
                + " refers to the environment variable "
                + variable
                + ", which is not set");
      }
      return found;
    }
    return raw;
  }

  private static Set<String> events(final Object value) {
    if (value == null) {
      return Set.copyOf(DEFAULT_EVENTS);
    }
    if (value instanceof final List<?> list && !list.isEmpty()) {
      return Set.copyOf(list.stream().map(String::valueOf).toList());
    }
    throw new IllegalArgumentException("events must list event type names");
  }

  private static boolean reviewOnError(final Object value) {
    if (value == null) {
      return true;
    }
    final String text = String.valueOf(value).strip().toUpperCase(Locale.ROOT);
    if ("DENY".equals(text) || "ALLOW".equals(text)) {
      return "DENY".equals(text);
    }
    throw new IllegalArgumentException("reviewOnError must be DENY or ALLOW");
  }

  @Override
  public SupervisorDescriptor describe() {
    return new SupervisorDescriptor(
        "Webhook",
        "Posts events, and optionally the commands to review, to a web service and acts on its"
            + " reply: commands it asks for, or a verdict on a command.",
        "Use it to hand decisions to a service you run. It sends nothing until allowedCommands"
            + " names the commands it may send, and it can only call hosts the deployment allows.",
        SupervisorCategory.INTEGRATION,
        Optional.of(new UiDesign("teal", "webhook", "Webhook", 140, 80)),
        Map.of(
            URL,
            "https://hooks.example.com/yukta",
            "events",
            DEFAULT_EVENTS,
            "allowedCommands",
            List.of("PauseWorkflowCommand")));
  }

  @Override
  public List<ConfigField> getConfigSchema() {
    return List.of(
        ConfigField.required(URL, Type.STRING, "The endpoint that receives the posts"),
        ConfigField.optional(
            "headers", Type.MAP, null, "Headers to send; a value may be ${env.NAME}"),
        ConfigField.optional(
            "secretHeaders", Type.SECRET, null, "Headers with secrets; never shown again"),
        ConfigField.optional("events", Type.LIST, DEFAULT_EVENTS, "The event types to post"),
        ConfigField.optional(
            "timeoutMs", Type.NUMBER, 3000, "How long to wait for a reply, at most 10000"),
        ConfigField.optional("review", Type.BOOLEAN, false, "Also post the commands to review"),
        ConfigField.choice(
            "reviewOnError", false, "DENY", "A review that fails is", "DENY", "ALLOW"),
        ConfigField.optional("maxConcurrent", Type.NUMBER, 4, "Most calls at once"));
  }
}
