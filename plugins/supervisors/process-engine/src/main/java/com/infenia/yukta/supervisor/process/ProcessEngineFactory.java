// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.supervisor.process;

import com.infenia.yukta.plugin.control.engine.Directive;
import com.infenia.yukta.plugin.control.engine.EngineConfig;
import com.infenia.yukta.plugin.control.engine.EngineContext;
import com.infenia.yukta.plugin.control.engine.EngineDescriptor;
import com.infenia.yukta.plugin.control.engine.Signal;
import com.infenia.yukta.plugin.control.engine.SupervisorEngine;
import com.infenia.yukta.plugin.control.engine.SupervisorEngineFactory;
import com.infenia.yukta.plugin.core.ConfigField;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * The {@code process} engine: a program on this server decides. Every signal is written to its
 * standard input as one JSON line ({@code {"kind": "EVENT", "signal": {...}}}); every line it
 * writes to standard output is read as a directive (see {@link DirectiveJson}). The program runs
 * with an empty environment and only if its executable is in {@code yukta.process.allowed-commands}
 * (or {@code yukta.process.allow-any} is set and that list is empty), the same rule as the process
 * node. A line that is not a directive is dropped; the supervisor checks the rest as it checks any
 * engine's. If the program exits, the engine fails and starts it again with backoff.
 */
@Slf4j
public final class ProcessEngineFactory implements SupervisorEngineFactory {

  /** The engine type. */
  public static final String TYPE = "process";

  private static final String COMMAND = "command";
  private static final String ARGS = "args";
  private static final int MAX_LINE = 65_536;

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final Set<String> allowed;
  private final boolean allowAny;

  /**
   * Creates the factory.
   *
   * @param allowed the executables that may run ({@code yukta.process.allowed-commands})
   * @param allowAny whether any executable may run when that list is empty
   */
  public ProcessEngineFactory(final Set<String> allowed, final boolean allowAny) {
    this.allowed = Set.copyOf(allowed);
    this.allowAny = allowAny;
  }

  @Override
  public String type() {
    return TYPE;
  }

  @Override
  public EngineDescriptor describe() {
    return new EngineDescriptor(
        "Program on this server",
        "A program reads the signals as JSON lines on standard input and writes directives as JSON"
            + " lines on standard output. It runs with an empty environment, and only if its"
            + " executable is in yukta.process.allowed-commands.",
        Set.of(Signal.Kind.EVENT, Signal.Kind.STARTED, Signal.Kind.DELEGATED),
        true);
  }

  @Override
  public List<ConfigField> getConfigSchema() {
    return List.of(
        ConfigField.required(COMMAND, ConfigField.Type.STRING, "The executable to run"),
        ConfigField.optional(
            ARGS, ConfigField.Type.STRING, null, "Arguments, one per line, in order"));
  }

  @Override
  public void validate(final EngineConfig config) {
    final String executable = executable(config);
    if (allowed.isEmpty() ? !allowAny : !allowed.contains(executable)) {
      throw new IllegalArgumentException(
          "The executable is not in yukta.process.allowed-commands: " + executable);
    }
  }

  @Override
  public SupervisorEngine create(final EngineConfig config, final EngineContext context) {
    validate(config);
    final List<String> command = new ArrayList<>();
    command.add(executable(config));
    command.addAll(args(config));
    final Process[] running = new Process[1];
    return new SupervisorEngine() {
      @Override
      public Flux<Directive> run(final Flux<Signal> signals) {
        return Flux.<Directive>create(
                sink -> {
                  final ProcessBuilder builder = new ProcessBuilder(command);
                  builder.environment().clear();
                  builder.redirectError(ProcessBuilder.Redirect.DISCARD);
                  final Process process;
                  try {
                    process = builder.start();
                  } catch (final IOException e) {
                    sink.error(e);
                    return;
                  }
                  running[0] = process;
                  final Writer stdin =
                      new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8);
                  final var feeding =
                      signals.subscribe(signal -> write(stdin, signal), sink::error);
                  sink.onDispose(
                      () -> {
                        feeding.dispose();
                        process.destroyForcibly();
                      });
                  Schedulers.boundedElastic().schedule(() -> read(process, sink));
                })
            .subscribeOn(Schedulers.boundedElastic());
      }

      @Override
      public void close() {
        if (running[0] != null) {
          running[0].destroyForcibly();
        }
      }
    };
  }

  private static void write(final Writer stdin, final Signal signal) {
    try {
      synchronized (stdin) {
        stdin.write(
            JSON.writeValueAsString(Map.of("kind", signal.kind().name(), "signal", signal)));
        stdin.write('\n');
        stdin.flush();
      }
    } catch (final IOException | JacksonException e) {
      log.atDebug().setCause(e).log("A signal could not be written to the program");
    }
  }

  @SuppressWarnings("unchecked")
  private static void read(
      final Process process, final reactor.core.publisher.FluxSink<Directive> sink) {
    try (BufferedReader out =
        new BufferedReader(
            new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
      String line;
      while ((line = out.readLine()) != null) {
        if (line.length() > MAX_LINE || line.isBlank()) {
          continue;
        }
        try {
          final Object parsed = JSON.readValue(line, Object.class);
          if (parsed instanceof Map<?, ?> map) {
            DirectiveJson.read((Map<String, Object>) map).ifPresent(sink::next);
          }
        } catch (final JacksonException | IllegalArgumentException | ClassCastException notJson) {
          log.atDebug().log("The program wrote a line that is not JSON; dropped");
        }
      }
      sink.error(new IllegalStateException("The program ended"));
    } catch (final IOException e) {
      sink.error(e);
    }
  }

  private static String executable(final EngineConfig config) {
    final Object value = config.settings().get(COMMAND);
    if (!(value instanceof String text) || text.isBlank()) {
      throw new IllegalArgumentException("command is required");
    }
    return text.strip();
  }

  private static List<String> args(final EngineConfig config) {
    final Object value = config.settings().get(ARGS);
    if (value instanceof List<?> list) {
      return list.stream().map(String::valueOf).toList();
    }
    return value == null || value.toString().isEmpty()
        ? List.of()
        : List.of(value.toString().split("\n"));
  }
}
