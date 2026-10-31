// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.io.RandomAccessFile;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * {@code yukta start|stop|status|restart|logs [-f]}: runs the server as a background process. The
 * state (pid file, log file) lives in {@code $YUKTA_HOME}, else {@code $XDG_STATE_HOME/yukta}, else
 * {@code ~/.local/state/yukta}. Handled before Spring starts, like {@code --version}.
 */
@SuppressWarnings({
  "PMD.TooManyMethods",
  "PMD.AvoidUsingHardCodedIP",
  "PMD.GodClass",
  "PMD.AvoidLiteralsInIfCondition"
})
/* default */ final class Daemon {

  /** The commands this class handles. */
  /* default */ static final Set<String> COMMANDS =
      Set.of("start", "stop", "status", "restart", "logs");

  /** Seconds to wait for the server to answer health, and to exit after a stop request. */
  private static final int WAIT_SECONDS = 60;

  /** Lines {@code logs} shows before it stops (or starts following). */
  private static final int TAIL_LINES = 100;

  private final Path home;
  private final Map<String, String> env;
  private final PrintStream out;

  /* default */ Daemon(final Path home, final Map<String, String> env, final PrintStream out) {
    this.home = home;
    this.env = env;
    this.out = out;
  }

  /** State directory from the environment. */
  /* default */ static Path homeFrom(final Map<String, String> env) {
    final String explicit = env.get("YUKTA_HOME");
    if (explicit != null && !explicit.isBlank()) {
      return Path.of(explicit);
    }
    final String xdg = env.get("XDG_STATE_HOME");
    final Path base =
        xdg != null && !xdg.isBlank()
            ? Path.of(xdg)
            : Path.of(System.getProperty("user.home"), ".local", "state");
    return base.resolve("yukta");
  }

  /** Whether the first argument is a daemon command. */
  /* default */ static boolean handles(final String... args) {
    return args.length > 0 && COMMANDS.contains(args[0]);
  }

  /** Runs the command in {@code args[0]}; the rest are passed to the server on start. */
  /* default */ int run(final String... args) throws IOException, InterruptedException {
    final List<String> rest = List.of(args).subList(1, args.length);
    return switch (args[0]) {
      case "start" -> start(rest);
      case "stop" -> stop();
      case "restart" -> {
        stop();
        yield start(rest);
      }
      case "status" -> status();
      default -> logs(rest.contains("-f"));
    };
  }

  private Path pidFile() {
    return home.resolve("yukta.pid");
  }

  private Path logFile() {
    return home.resolve("yukta.log");
  }

  private String baseUrl() {
    final String addr = env.getOrDefault("YUKTA_SERVER_ADDRESS", "127.0.0.1");
    final String host = addr.isBlank() || "0.0.0.0".equals(addr) ? "127.0.0.1" : addr;
    return "http://" + host + ":" + env.getOrDefault("SERVER_PORT", "9858");
  }

  /** The running server's process: the pid file names it and it started when the file says. */
  private Optional<ProcessHandle> running() throws IOException {
    if (!Files.exists(pidFile())) {
      return Optional.empty();
    }
    final String[] parts = Files.readString(pidFile()).strip().split("\\s+", -1);
    final Optional<ProcessHandle> handle = ProcessHandle.of(Long.parseLong(parts[0]));
    // the start time guards against a recycled pid
    return handle.filter(
        h ->
            h.isAlive()
                && h.info()
                    .startInstant()
                    .map(
                        t ->
                            parts.length < 2
                                || Math.abs(t.toEpochMilli() - Long.parseLong(parts[1])) < 2000)
                    .orElse(true));
  }

  private int start(final List<String> serverArgs) throws IOException, InterruptedException {
    if (running().isPresent()) {
      out.println("yukta is already running (pid " + running().get().pid() + ")");
      return 0;
    }
    Files.createDirectories(home);
    final List<String> cmd = new ArrayList<>();
    if (onPath("setsid")) {
      cmd.add("setsid"); // own session, so closing the terminal does not stop the server
    }
    cmd.addAll(selfCommand());
    cmd.add("--mode=plain");
    cmd.addAll(serverArgs);
    final Process p =
        new ProcessBuilder(cmd)
            .redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")))
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile().toFile()))
            .start();
    final long started =
        p.toHandle().info().startInstant().map(java.time.Instant::toEpochMilli).orElse(0L);
    Files.writeString(pidFile(), p.pid() + " " + started);
    for (int i = 0; i < WAIT_SECONDS; i++) {
      if (!p.isAlive()) {
        Files.deleteIfExists(pidFile());
        out.println("yukta exited during start; see " + logFile());
        return 1;
      }
      if (health().isPresent()) {
        out.println("yukta started (pid " + p.pid() + ") at " + baseUrl());
        return 0;
      }
      Thread.sleep(1000);
    }
    out.println("yukta did not become healthy in " + WAIT_SECONDS + "s; see " + logFile());
    return 1;
  }

  private static boolean onPath(final String exe) {
    final String path = System.getenv("PATH");
    return path != null
        && java.util.Arrays.stream(path.split(File.pathSeparator))
            .anyMatch(d -> new File(d, exe).canExecute());
  }

  /** The command line that starts this same program: a native binary, a jar or a classpath. */
  private static List<String> selfCommand() {
    final String exe = ProcessHandle.current().info().command().orElseThrow();
    if ("runtime".equals(System.getProperty("org.graalvm.nativeimage.imagecode"))) {
      return List.of(exe);
    }
    final String cp = System.getProperty("java.class.path");
    return cp.endsWith(".jar") && !cp.contains(File.pathSeparator)
        ? List.of(exe, "-jar", cp)
        : List.of(exe, "-cp", cp, YuktaApplication.class.getName());
  }

  /** The HTTP status of the health endpoint, empty while nothing answers. */
  private Optional<Integer> health() {
    try {
      final HttpResponse<Void> r =
          HttpClient.newHttpClient()
              .send(
                  HttpRequest.newBuilder(URI.create(baseUrl() + "/actuator/health"))
                      .timeout(Duration.ofSeconds(2))
                      .build(),
                  HttpResponse.BodyHandlers.discarding());
      return Optional.of(r.statusCode());
    } catch (IOException e) {
      return Optional.empty();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return Optional.empty();
    }
  }

  private int stop() throws IOException, InterruptedException {
    final Optional<ProcessHandle> proc = running();
    if (proc.isEmpty()) {
      Files.deleteIfExists(pidFile());
      out.println("yukta is not running");
      return 0;
    }
    final ProcessHandle h = proc.get();
    drain();
    h.destroy(); // SIGTERM
    try {
      h.onExit().get(WAIT_SECONDS / 2, java.util.concurrent.TimeUnit.SECONDS);
    } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
      h.destroyForcibly(); // SIGKILL
      h.onExit().join();
    }
    Files.deleteIfExists(pidFile());
    out.println("yukta stopped");
    return 0;
  }

  /** Asks the server to refuse new runs; best effort, since SIGTERM follows regardless. */
  private void drain() throws InterruptedException {
    final HttpRequest.Builder b =
        HttpRequest.newBuilder(URI.create(baseUrl() + "/api/admin/drain"))
            .timeout(Duration.ofSeconds(5))
            .POST(HttpRequest.BodyPublishers.noBody());
    final String key = env.get("YUKTA_API_KEY");
    if (key != null && !key.isBlank()) {
      b.header("Authorization", "Bearer " + key);
    }
    try {
      HttpClient.newHttpClient().send(b.build(), HttpResponse.BodyHandlers.discarding());
    } catch (IOException ignored) {
      // server not answering: go on to the signal
    }
  }

  private int status() throws IOException {
    final Optional<ProcessHandle> proc = running();
    if (proc.isEmpty()) {
      out.println("yukta is not running");
      return 3; // the init-script convention for "stopped"
    }
    out.println(
        "yukta is running (pid "
            + proc.get().pid()
            + ") at "
            + baseUrl()
            + ", health: "
            + health().map(c -> c == 200 ? "UP" : "HTTP " + c).orElse("not answering"));
    return 0;
  }

  private int logs(final boolean follow) throws IOException, InterruptedException {
    if (!Files.exists(logFile())) {
      out.println("no log yet at " + logFile());
      return 1;
    }
    try (RandomAccessFile f = new RandomAccessFile(logFile().toFile(), "r")) {
      final List<String> lines = Files.readAllLines(logFile());
      lines.subList(Math.max(0, lines.size() - TAIL_LINES), lines.size()).forEach(out::println);
      f.seek(f.length());
      while (follow && !Thread.currentThread().isInterrupted()) {
        final String line = f.readLine();
        if (line == null) {
          Thread.sleep(500);
        } else {
          out.println(line);
        }
      }
    }
    return 0;
  }
}
