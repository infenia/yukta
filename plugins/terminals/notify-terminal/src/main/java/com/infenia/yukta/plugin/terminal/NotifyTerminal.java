// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.terminal;

import com.infenia.yukta.message.Message;
import com.infenia.yukta.plugin.core.ConfigField;
import com.infenia.yukta.plugin.core.FailureMode;
import com.infenia.yukta.plugin.core.PluginDescriptor;
import com.infenia.yukta.plugin.core.PluginGuard;
import com.infenia.yukta.plugin.core.PluginMetricsReporter;
import com.infenia.yukta.plugin.core.PluginTier;
import com.infenia.yukta.plugin.core.UiDesign;
import com.infenia.yukta.plugin.notify.NotificationGateway;
import com.infenia.yukta.plugin.notify.NotificationSeverity;
import com.infenia.yukta.plugin.notify.NotificationSpec;
import com.infenia.yukta.plugin.type.TerminalPlugin;
import com.infenia.yukta.plugin.util.ConfigValidation;
import com.infenia.yukta.plugin.util.EgressPolicy;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Notifies per message: by default it posts a {@code {"text": ...}} JSON notification
 * (Slack/Teams/Discord-compatible) to a webhook; with {@code target: inbox} it raises a
 * notification in the Yukta inbox instead. The run, node and session of an inbox notification are
 * the node's own: a workflow cannot choose them.
 */
@Component
@Slf4j
@SuppressWarnings("PMD.TooManyMethods")
public final class NotifyTerminal implements TerminalPlugin {

  /** Config key of the failure mode. */
  private static final String FAILURE_MODE = "failureMode";

  /** Config key that picks where the notification goes. */
  private static final String TARGET_KEY = "target";

  /** The default target: a webhook. */
  private static final String WEBHOOK = "webhook";

  /** The target that raises a notification in the Yukta inbox. */
  private static final String INBOX = "inbox";

  /** Config key and webhook body field holding the notification text. */
  private static final String TEXT_KEY = "text";

  /** Request timeout unless {@code timeoutMs} says otherwise. */
  private static final long DEFAULT_TIMEOUT_MS = 10_000L;

  /** Builder used to create the WebClient. */
  private final WebClient.Builder webClientBuilder;

  /** Metrics reporter. */
  private final PluginMetricsReporter reporter;

  /** Raises the notifications of {@code target: inbox}; absent when no gateway bean exists. */
  private ObjectProvider<NotificationGateway> gateway;

  /** Where the deployment, workflow and session allow calls to go. */
  private EgressPolicy egress = EgressPolicy.OPEN;

  /**
   * Creates a terminal without metrics.
   *
   * @param webClientBuilder builder for the webhook client
   */
  public NotifyTerminal(final WebClient.Builder webClientBuilder) {
    this.webClientBuilder = webClientBuilder;
    this.reporter = PluginMetricsReporter.NOOP;
  }

  /**
   * Spring constructor.
   *
   * @param webClientBuilder builder for the webhook client
   * @param reporterProvider optional metrics reporter
   */
  @Autowired
  public NotifyTerminal(
      final WebClient.Builder webClientBuilder,
      final ObjectProvider<PluginMetricsReporter> reporterProvider) {
    this.webClientBuilder = webClientBuilder;
    this.reporter = reporterProvider.getIfAvailable(() -> PluginMetricsReporter.NOOP);
  }

  /**
   * Sets the deployment, workflow and session host lists ({@code yukta.egress.*}).
   *
   * @param policy the policy
   */
  @Autowired(required = false)
  /* default */ void setEgressPolicy(final EgressPolicy policy) {
    this.egress = policy;
  }

  /**
   * Sets the gateway for {@code target: inbox}.
   *
   * @param provider the gateway provider
   */
  @Autowired(required = false)
  /* default */ void setNotificationGateway(final ObjectProvider<NotificationGateway> provider) {
    this.gateway = provider;
  }

  @Override
  public PluginDescriptor describe() {
    return new PluginDescriptor(
        "Sends each message as a JSON webhook notification (Slack-compatible).",
        "Configure with:\n"
            + "- target: webhook (default) or inbox, the Yukta inbox.\n"
            + "- url: webhook URL (required for target webhook, https; http only for localhost).\n"
            + "- title, severity (INFO, WARN, ERROR) and audience (role:VIEWER, role:OPERATOR,"
            + " role:ADMIN or key:<name>; default role:OPERATOR): for target inbox, where the text"
            + " is the body.\n"
            + "- text: fixed text to send; otherwise the payload's 'text' field, or the payload"
            + " itself as a string.\n"
            + "- timeoutMs: request timeout (default 10000).\n"
            + "- failureMode: ERROR (default) fails the node on a non-2xx response (including"
            + " redirects); CONTINUE logs it and keeps sending.\n",
        Optional.of(new UiDesign("slate", "notifications", "Notify", 140, 80)));
  }

  @Override
  public List<ConfigField> getConfigSchema() {
    return List.of(
        ConfigField.choice(
            TARGET_KEY,
            false,
            WEBHOOK,
            "webhook posts to a URL; inbox raises a notification in the Yukta inbox",
            WEBHOOK,
            INBOX),
        ConfigField.optional(
            "url",
            ConfigField.Type.STRING,
            null,
            "Webhook URL (required for target webhook; https, http only for localhost)"),
        ConfigField.optional(
            TEXT_KEY, ConfigField.Type.STRING, null, "Fixed text; otherwise the payload text"),
        ConfigField.optional("timeoutMs", ConfigField.Type.NUMBER, 10_000, "Request timeout in ms"),
        ConfigField.optional(
            "title", ConfigField.Type.STRING, null, "Inbox title (required for target inbox)"),
        ConfigField.choice(
            "severity",
            false,
            "INFO",
            "Severity of an inbox notification",
            "INFO",
            "WARN",
            "ERROR"),
        ConfigField.optional(
            "audience",
            ConfigField.Type.LIST,
            null,
            "Who sees an inbox notification: role:VIEWER, role:OPERATOR, role:ADMIN or key:<name>"),
        ConfigField.choice(
            FAILURE_MODE,
            false,
            "ERROR",
            "ERROR fails the node on a failed send; CONTINUE logs it and keeps sending",
            "ERROR",
            "CONTINUE"));
  }

  @Override
  public PluginTier getTier() {
    return PluginTier.SPECIFIC;
  }

  @Override
  public boolean isRestricted() {
    return true;
  }

  @Override
  public String getType() {
    return "NOTIFY_TERMINAL";
  }

  @Override
  public Mono<Void> validateConfig(final Map<String, Object> config) {
    return Mono.fromRunnable(() -> {
      if (isInbox(config)) {
        spec(config, "");
      } else {
        checkUrl(ConfigValidation.requiredString(config, "url"));
      }
      ConfigValidation.positiveLong(config, "timeoutMs", DEFAULT_TIMEOUT_MS);
      FailureMode.from(config.get(FAILURE_MODE));
    });
  }

  @Override
  public Mono<Void> consume(final Flux<Message<?>> input, final Map<String, Object> config) {
    if (isInbox(config)) {
      return consumeInbox(input, config);
    }
    final String url = ConfigValidation.requiredString(config, "url");
    checkUrl(url);
    final Duration timeout =
        Duration.ofMillis(ConfigValidation.positiveLong(config, "timeoutMs", DEFAULT_TIMEOUT_MS));
    final FailureMode mode = FailureMode.from(config.get(FAILURE_MODE));
    final WebClient client = webClientBuilder.build();
    return input
        .concatMap(message ->
            PluginGuard.guard(
                send(client, url, timeout, message, config).<Message<?>>thenReturn(message),
                message,
                mode,
                reporter,
                "notify"))
        .doOnNext(m -> {
          if (PluginGuard.ERROR_PORT.equals(m.sourcePort())) {
            log.atWarn().log("Notification failed: {}", m.exceptionDetail());
          }
        })
        .then();
  }

  /** Raises a notification in the inbox per message. */
  private Mono<Void> consumeInbox(final Flux<Message<?>> input, final Map<String, Object> config) {
    spec(config, "");
    final NotificationGateway notifications = gateway == null ? null : gateway.getIfAvailable();
    if (notifications == null) {
      return Mono.error(
          new IllegalStateException(
              "target inbox needs the notification gateway, which is absent"));
    }
    final FailureMode mode = FailureMode.from(config.get(FAILURE_MODE));
    return input
        .concatMap(message ->
            PluginGuard.guard(
                notifications
                    .notify(spec(config, text(message, config)))
                    .<Message<?>>thenReturn(message),
                message,
                mode,
                reporter,
                "notify"))
        .doOnNext(m -> {
          if (PluginGuard.ERROR_PORT.equals(m.sourcePort())) {
            log.atWarn().log("Notification failed: {}", m.exceptionDetail());
          }
        })
        .then();
  }

  private static boolean isInbox(final Map<String, Object> config) {
    final Object target = config.get(TARGET_KEY);
    if (target == null || WEBHOOK.equalsIgnoreCase(String.valueOf(target))) {
      return false;
    }
    if (INBOX.equalsIgnoreCase(String.valueOf(target))) {
      return true;
    }
    throw new IllegalArgumentException("target must be webhook or inbox, not '" + target + "'");
  }

  /** The inbox notification for a text; the title, severity and audience come from the config. */
  private static NotificationSpec spec(final Map<String, Object> config, final String body) {
    final String title = ConfigValidation.requiredString(config, "title");
    final Object severity = config.get("severity");
    final Object audience = config.get("audience");
    final List<String> selectors =
        audience == null
            ? List.of()
            : audience instanceof List<?> list
                ? list.stream().map(String::valueOf).toList()
                : List.of(String.valueOf(audience).split("\\s*,\\s*"));
    return new NotificationSpec(
        title,
        body.isEmpty() ? null : body,
        severity == null ? NotificationSeverity.INFO : severity(String.valueOf(severity)),
        selectors,
        null,
        List.of());
  }

  private static NotificationSeverity severity(final String value) {
    try {
      return NotificationSeverity.valueOf(value.strip().toUpperCase(Locale.ROOT));
    } catch (final IllegalArgumentException e) {
      throw new IllegalArgumentException("severity must be INFO, WARN or ERROR", e);
    }
  }

  private Mono<Void> send(
      final WebClient client,
      final String url,
      final Duration timeout,
      final Message<?> message,
      final Map<String, Object> config) {
    return egress.check(url).then(Mono.defer(() -> post(client, url, timeout, message, config)));
  }

  private Mono<Void> post(
      final WebClient client,
      final String url,
      final Duration timeout,
      final Message<?> message,
      final Map<String, Object> config) {
    return client
        .post()
        .uri(url)
        .bodyValue(Map.of(TEXT_KEY, text(message, config)))
        .<Void>exchangeToMono(response ->
            response
                .releaseBody()
                .then(
                    response.statusCode().is2xxSuccessful()
                        ? Mono.empty()
                        : Mono.error(
                            new IllegalStateException(
                                "Webhook answered " + response.statusCode()))))
        .timeout(timeout);
  }

  /** Webhooks may carry secrets, so only https is allowed, plus http to the local machine. */
  private static void checkUrl(final String url) {
    final URI uri;
    try {
      uri = URI.create(url);
    } catch (final IllegalArgumentException e) {
      throw new IllegalArgumentException("url is not a valid URL", e);
    }
    final String host = String.valueOf(uri.getHost());
    final boolean loopback =
        "localhost".equalsIgnoreCase(host) || "[::1]".equals(host) || host.startsWith("127.");
    if (!"https".equals(uri.getScheme()) && !("http".equals(uri.getScheme()) && loopback)) {
      throw new IllegalArgumentException("url must be https (http only for localhost)");
    }
  }

  private static String text(final Message<?> message, final Map<String, Object> config) {
    final Object payload = message.payload();
    final Object fixed = config.get(TEXT_KEY);
    final Object field = payload instanceof Map<?, ?> map ? map.get(TEXT_KEY) : null;
    return String.valueOf(fixed != null ? fixed : field != null ? field : payload);
  }
}
