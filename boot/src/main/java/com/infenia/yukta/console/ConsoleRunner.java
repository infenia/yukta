// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.console;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jline.keymap.BindingReader;
import org.jline.keymap.KeyMap;
import org.jline.terminal.Attributes;
import org.jline.terminal.MouseEvent;
import org.jline.terminal.Size;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.AttributedString;
import org.jline.utils.Display;
import org.jline.utils.InfoCmp.Capability;
import org.jline.utils.NonBlockingReader;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The interactive console: a full-screen dashboard on the terminal, fed by the server's own REST
 * API. Does nothing unless the run mode is {@code console}; q stops the server. The terminal is
 * restored on exit and on any failure.
 */
@Component
@SuppressWarnings({
  "PMD.SystemPrintln",
  "PMD.AvoidCatchingGenericException",
  "PMD.DoNotTerminateVM",
  "PMD.CloseResource",
  "PMD.ConfusingTernary",
  "PMD.AvoidLiteralsInIfCondition"
})
public class ConsoleRunner implements ApplicationListener<ApplicationReadyEvent> {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final HttpClient HTTP = HttpClient.newHttpClient();
  private static final int TICK_MS = 100;

  @Override
  public void onApplicationEvent(final ApplicationReadyEvent event) {
    final ConfigurableApplicationContext ctx = event.getApplicationContext();
    // checked here, not with @ConditionalOnProperty: Spring AOT would freeze that at build time
    if (!"console".equals(ctx.getEnvironment().getProperty("yukta.run-mode"))) {
      return;
    }
    final String port = ctx.getEnvironment().getProperty("local.server.port", "9858");
    final String key = ctx.getEnvironment().getProperty("yukta.api-key", "");
    final String version =
        ctx.getBeanProvider(BuildProperties.class).stream()
            .findFirst()
            .map(BuildProperties::getVersion)
            .orElse("dev");
    final ConsoleView view =
        new ConsoleView(
            version,
            "http://127.0.0.1:" + port,
            ConsoleLogs.install(),
            ConsoleView.colorWanted(System.getenv()));
    Thread.ofPlatform()
        .name("yukta-console")
        .daemon(true)
        .start(
            () -> {
              int code = 0;
              try {
                loop(view, "http://127.0.0.1:" + port, key);
              } catch (Exception | LinkageError e) {
                System.err.println("console failed: " + e);
                code = 1;
              }
              final int exit = SpringApplication.exit(ctx, () -> 0);
              System.exit(code != 0 ? code : exit);
            });
  }

  private static void loop(final ConsoleView view, final String base, final String key)
      throws IOException {
    final Instant started = Instant.now();
    ConsoleView.Snapshot snap = ConsoleView.Snapshot.NONE;
    Instant lastPoll = Instant.EPOCH;
    try (Terminal term = TerminalBuilder.builder().system(true).build()) {
      final var saved = term.enterRawMode();
      // the terminal is put back even if the JVM is killed
      final Thread restore = new Thread(() -> leave(term));
      Runtime.getRuntime().addShutdownHook(restore);
      term.puts(Capability.enter_ca_mode);
      term.puts(Capability.cursor_invisible);
      term.puts(Capability.keypad_xmit);
      final boolean graphemes =
          term.supportsGraphemeClusterMode() && term.setGraphemeClusterMode(true, false);
      boolean mouse = term.hasMouseSupport() && term.trackMouse(Terminal.MouseTracking.Normal);
      term.flush();
      final AtomicBoolean dirty = new AtomicBoolean(true);
      term.handle(Terminal.Signal.WINCH, sig -> dirty.set(true));
      final Display display = new Display(term, true);
      final KeyMap<String> keys = keys(term);
      final BindingReader reader = new BindingReader(term.reader());
      try {
        Size size = term.getSize();
        display.resize(size.getRows(), size.getColumns());
        while (!view.quit()) {
          if (Duration.between(lastPoll, Instant.now()).toMillis() >= 1000) {
            snap = poll(base, key, view.wantsManyRuns() ? 50 : 8);
            lastPoll = Instant.now();
          }
          if (dirty.getAndSet(false)) {
            size = term.getSize();
            display.resize(size.getRows(), size.getColumns());
            display.clear();
          }
          final List<AttributedString> lines =
              view.render(
                  snap,
                  Duration.between(started, Instant.now()),
                  size.getColumns(),
                  size.getRows());
          term.beginSynchronizedUpdate();
          display.update(lines, 0);
          term.endSynchronizedUpdate();
          term.flush();
          final String k = key(term, reader, keys);
          if ("m".equals(k) && term.hasMouseSupport()) {
            mouse = toggleMouse(term, mouse);
          } else if (k != null) {
            view.onKey(k);
          }
        }
      } finally {
        Runtime.getRuntime().removeShutdownHook(restore);
        restore(term, graphemes, mouse, saved);
      }
    }
  }

  private static boolean toggleMouse(final Terminal term, final boolean mouse) {
    return term.trackMouse(mouse ? Terminal.MouseTracking.Off : Terminal.MouseTracking.Normal)
        != mouse;
  }

  private static void restore(
      final Terminal term, final boolean graphemes, final boolean mouse, final Attributes saved) {
    if (graphemes) {
      term.setGraphemeClusterMode(false, false);
    }
    if (mouse) {
      term.trackMouse(Terminal.MouseTracking.Off);
    }
    leave(term);
    term.setAttributes(saved);
    ConsoleLogs.restore();
  }

  private static void leave(final Terminal term) {
    term.puts(Capability.keypad_local);
    term.puts(Capability.cursor_normal);
    term.puts(Capability.exit_ca_mode);
    term.flush();
  }

  /** The keys the console reacts to, by name (see {@link ConsoleView#onKey}). */
  private static KeyMap<String> keys(final Terminal term) {
    final KeyMap<String> map = new KeyMap<>();
    for (final String c : List.of("q", "l", "r", "?", "f", "G", "m")) {
      map.bind(c, c);
    }
    // both the normal ([A) and the application keypad (OA) forms, plus what terminfo says
    bindKey(map, "UP", term, Capability.key_up, "\033[A", "\033OA");
    bindKey(map, "DOWN", term, Capability.key_down, "\033[B", "\033OB");
    bindKey(map, "PGUP", term, Capability.key_ppage, "\033[5~");
    bindKey(map, "PGDN", term, Capability.key_npage, "\033[6~");
    bindKey(map, "HOME", term, Capability.key_home, "\033[H", "\033OH", "\033[1~");
    bindKey(map, "END", term, Capability.key_end, "\033[F", "\033OF", "\033[4~");
    if (term.hasMouseSupport()) {
      map.bind("MOUSE", KeyMap.key(term, Capability.key_mouse));
    }
    map.setNomatch("");
    map.setUnicode("");
    return map;
  }

  private static void bindKey(
      final KeyMap<String> map,
      final String name,
      final Terminal term,
      final Capability cap,
      final String... fixed) {
    final String terminfo = KeyMap.key(term, cap);
    if (terminfo != null) {
      map.bind(name, terminfo);
    }
    for (final String f : fixed) {
      map.bind(name, f);
    }
  }

  /** Waits up to one tick for a key; null on timeout or for a key the console ignores. */
  private static String key(
      final Terminal term, final BindingReader reader, final KeyMap<String> keys)
      throws IOException {
    if (term.reader().peek(TICK_MS) == NonBlockingReader.READ_EXPIRED) {
      return null;
    }
    final String name = reader.readBinding(keys);
    if ("MOUSE".equals(name)) {
      final MouseEvent e = term.readMouseEvent();
      if (e.getType() == MouseEvent.Type.Wheel) {
        return e.getButton() == MouseEvent.Button.WheelUp ? "UP" : "DOWN";
      }
      return null;
    }
    return name == null || name.isEmpty() ? null : name;
  }

  private static ConsoleView.Snapshot poll(final String base, final String key, final int limit) {
    try {
      final JsonNode counts = get(base + "/api/executions/counts", key).path("data");
      final JsonNode runs = get(base + "/api/executions?limit=" + limit, key).path("data");
      final List<ConsoleView.Run> rows = new ArrayList<>();
      for (final JsonNode r : runs) {
        rows.add(
            new ConsoleView.Run(
                r.path("workflowId").asString(""),
                r.path("status").asString(""),
                r.path("startTime").asString("").replace('T', ' '),
                r.path("initiator").asString("")));
      }
      final String health = get(base + "/actuator/health", key).path("status").asString("?");
      return new ConsoleView.Snapshot(
          health,
          counts.path("running").asInt(0),
          counts.path("queued").asInt(0),
          counts.path("failed").asInt(0),
          rows);
    } catch (IOException | RuntimeException e) {
      return new ConsoleView.Snapshot("DOWN", 0, 0, 0, List.of());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return ConsoleView.Snapshot.NONE;
    }
  }

  private static JsonNode get(final String url, final String key)
      throws IOException, InterruptedException {
    final HttpRequest.Builder b =
        HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(2));
    if (!key.isBlank()) {
      b.header("Authorization", "Bearer " + key);
    }
    return JSON.readTree(HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString()).body());
  }
}
