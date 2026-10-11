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
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Emits a message on a fixed interval or a cron schedule. Config: {@code intervalMs} or {@code
 * cron} (Spring 6-field cron, optional {@code zone}), plus optional {@code maxRuns}.
 */
@Component
public class ScheduleTrigger implements TriggerPlugin {

  /** Config key: cron expression. */
  private static final String CRON = "cron";

  /** Config key: fixed interval. */
  private static final String INTERVAL_MS = "intervalMs";

  /** Config key: emission cap. */
  private static final String MAX_RUNS = "maxRuns";

  /** Default constructor. */
  public ScheduleTrigger() {
    super();
  }

  @Override
  public PluginDescriptor describe() {
    return new PluginDescriptor(
        "Emits a message repeatedly on a fixed interval or a cron schedule.",
        "Configure with:\n"
            + "- intervalMs: fixed delay between runs in milliseconds (first run after one"
            + " interval).\n"
            + "- cron: Spring 6-field cron expression (alternative to intervalMs).\n"
            + "- zone: time zone for cron, default system zone.\n"
            + "- maxRuns: stop after this many emissions (default unbounded).\n"
            + "Payload: {tick, firedAt}.",
        Optional.of(
            new UiDesign(
                """
                <div class="flex items-center w-full h-full bg-green-50/50 border-green-100 border-2 rounded-xl px-4 gap-3">
                    <div class="flex-shrink-0 w-8 h-8 bg-green-100 text-green-500 rounded-lg flex items-center justify-center">
                        <svg class="w-5 h-5" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" viewBox="0 0 24 24"><circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 3"/></svg>
                    </div>
                    <div class="flex flex-col min-w-0">
                        <div class="text-[10px] text-green-600 font-bold uppercase tracking-wider leading-none mb-1">Schedule</div>
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
        ConfigField.optional(
            "intervalMs", ConfigField.Type.NUMBER, null, "Fixed delay in ms; set this or cron"),
        ConfigField.optional(
            "cron", ConfigField.Type.STRING, null, "Spring 6-field cron; set this or intervalMs"),
        ConfigField.optional(
            "zone", ConfigField.Type.STRING, null, "Time zone for cron (default: system zone)"),
        ConfigField.optional(
            "maxRuns", ConfigField.Type.NUMBER, null, "Stop after this many emissions"));
  }

  @Override
  public String getType() {
    return "SCHEDULE_TRIGGER";
  }

  @Override
  public Mono<Void> validateConfig(final Map<String, Object> config) {
    return Mono.fromRunnable(() -> schedule(config));
  }

  @Override
  public Flux<Message<?>> start(final Map<String, Object> config) {
    final Flux<Message<?>> messages =
        schedule(config).index().map(t -> message(t.getT1() + 1, Instant.now()));
    return config.get(MAX_RUNS) == null
        ? messages
        : messages.take(ConfigValidation.positiveLong(config, MAX_RUNS, 1));
  }

  private static Flux<?> schedule(final Map<String, Object> config) {
    if (config.get(MAX_RUNS) != null) {
      ConfigValidation.positiveLong(config, MAX_RUNS, 1);
    }
    final Object cron = config.get(CRON);
    final Object interval = config.get(INTERVAL_MS);
    if ((cron != null) == (interval != null)) {
      throw new IllegalArgumentException("Exactly one of 'cron' or 'intervalMs' is required");
    }
    final Flux<?> result;
    if (interval instanceof Number millis) {
      if (millis.longValue() <= 0) {
        throw new IllegalArgumentException("'intervalMs' must be positive");
      }
      result = Flux.interval(Duration.ofMillis(millis.longValue()));
    } else {
      final CronExpression expr = CronExpression.parse(String.valueOf(cron));
      final ZoneId zone =
          ZoneId.of(String.valueOf(config.getOrDefault("zone", ZoneId.systemDefault())));
      if (expr.next(ZonedDateTime.now(zone)) == null) {
        throw new IllegalArgumentException("cron has no future execution: " + cron);
      }
      result = repeatWhileScheduled(expr::next, zone);
    }
    return result;
  }

  /**
   * Fires after each delay to the next execution, and ends instead of spinning once there is none.
   */
  /* default */ static Flux<?> repeatWhileScheduled(
      final java.util.function.Function<ZonedDateTime, ZonedDateTime> next, final ZoneId zone) {
    return Mono.defer(
            () -> {
              final ZonedDateTime now = ZonedDateTime.now(zone);
              return Mono.delay(Duration.between(now, next.apply(now)));
            })
        .repeat(() -> next.apply(ZonedDateTime.now(zone)) != null);
  }

  private static Message<?> message(final long tick, final Instant firedAt) {
    return DefaultMessage.create(
            UUID.randomUUID(), Map.of("tick", tick, "firedAt", firedAt.toString()))
        .withSourcePort("default");
  }
}
