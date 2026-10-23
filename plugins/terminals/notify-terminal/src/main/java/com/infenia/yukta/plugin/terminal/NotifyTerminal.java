// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.terminal;

import com.infenia.yukta.message.Message;
import com.infenia.yukta.plugin.core.ConfigField;
import com.infenia.yukta.plugin.core.FailureMode;
import com.infenia.yukta.plugin.core.PluginDescriptor;
import com.infenia.yukta.plugin.core.PluginGuard;
import com.infenia.yukta.plugin.core.PluginMetricsReporter;
import com.infenia.yukta.plugin.core.UiDesign;
import com.infenia.yukta.plugin.type.TerminalPlugin;
import com.infenia.yukta.plugin.util.ConfigValidation;
import com.infenia.yukta.plugin.util.EgressPolicy;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Posts a {@code {"text": ...}} JSON notification (Slack/Teams/Discord-compatible) per message. */
@Component
@Slf4j
public final class NotifyTerminal implements TerminalPlugin {

  /** Config key and webhook body field holding the notification text. */
  private static final String TEXT_KEY = "text";

  /** Request timeout unless {@code timeoutMs} says otherwise. */
  private static final long DEFAULT_TIMEOUT_MS = 10_000L;

  /** Builder used to create the WebClient. */
  private final WebClient.Builder webClientBuilder;

  /** Metrics reporter. */
  private final PluginMetricsReporter reporter;

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

  @Override
  public PluginDescriptor describe() {
    return new PluginDescriptor(
        "Sends each message as a JSON webhook notification (Slack-compatible).",
        "Configure with:\n"
            + "- url: webhook URL (required, https; http only for localhost).\n"
            + "- text: fixed text to send; otherwise the payload's 'text' field, or the payload"
            + " itself as a string.\n"
            + "- timeoutMs: request timeout (default 10000).\n"
            + "- failureMode: ERROR (default) fails the node on a non-2xx response (including"
            + " redirects); CONTINUE logs it and keeps sending.\n",
        Optional.of(
            new UiDesign(
                """
                <div class="flex items-center w-full h-full bg-slate-50 border-slate-200 border-2 rounded-xl px-4 gap-3">
                    <div class="flex-shrink-0 w-8 h-8 bg-slate-100 text-slate-500 rounded-lg flex items-center justify-center">
                        <svg class="w-5 h-5" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" viewBox="0 0 24 24"><path d="M18 8a6 6 0 10-12 0c0 7-3 9-3 9h18s-3-2-3-9M13.7 21a2 2 0 01-3.4 0"/></svg>
                    </div>
                    <div class="flex flex-col min-w-0">
                        <div class="text-[10px] text-slate-400 font-bold uppercase tracking-wider leading-none mb-1">Notify</div>
                        <div class="text-xs font-bold text-slate-700 truncate w-full">{{nodeId}}</div>
                    </div>
                </div>
                """,
                140,
                80)));
  }

  @Override
  public List<ConfigField> getConfigSchema() {
    return List.of(
        ConfigField.required(
            "url", ConfigField.Type.STRING, "Webhook URL (https; http only for localhost)"),
        ConfigField.optional(
            TEXT_KEY, ConfigField.Type.STRING, null, "Fixed text; otherwise the payload text"),
        ConfigField.optional("timeoutMs", ConfigField.Type.NUMBER, 10_000, "Request timeout in ms"),
        ConfigField.choice(
            "failureMode",
            false,
            "ERROR",
            "ERROR fails the node on a failed send; CONTINUE logs it and keeps sending",
            "ERROR",
            "CONTINUE"));
  }

  @Override
  public String getType() {
    return "NOTIFY_TERMINAL";
  }

  @Override
  public Mono<Void> validateConfig(final Map<String, Object> config) {
    return Mono.fromRunnable(
        () -> {
          checkUrl(ConfigValidation.requiredString(config, "url"));
          ConfigValidation.positiveLong(config, "timeoutMs", DEFAULT_TIMEOUT_MS);
          FailureMode.from(config.get("failureMode"));
        });
  }

  @Override
  public Mono<Void> consume(final Flux<Message<?>> input, final Map<String, Object> config) {
    final String url = ConfigValidation.requiredString(config, "url");
    checkUrl(url);
    final Duration timeout =
        Duration.ofMillis(ConfigValidation.positiveLong(config, "timeoutMs", DEFAULT_TIMEOUT_MS));
    final FailureMode mode = FailureMode.from(config.get("failureMode"));
    final WebClient client = webClientBuilder.build();
    return input
        .concatMap(
            message ->
                PluginGuard.guard(
                    send(client, url, timeout, message, config).<Message<?>>thenReturn(message),
                    message,
                    mode,
                    reporter,
                    "notify"))
        .doOnNext(
            m -> {
              if (PluginGuard.ERROR_PORT.equals(m.sourcePort())) {
                log.atWarn().log("Notification failed: {}", m.exceptionDetail());
              }
            })
        .then();
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
        .<Void>exchangeToMono(
            response ->
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
