// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.external;

import com.infenia.yukta.plugin.core.PluginLogSink;
import com.infenia.yukta.plugin.process.ProcessExecutionResult;
import com.infenia.yukta.plugin.process.ProcessExecutionSpec;
import com.infenia.yukta.plugin.process.ProcessExecutorGateway;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

/**
 * The container CLI that runs {@code run.image} plugins ({@code docker}, {@code podman} or {@code
 * nerdctl}: they take the same arguments), and the operator's extra {@code run} arguments per
 * plugin type. Yukta calls the CLI, not a daemon API, so no client library is needed and the native
 * image works.
 *
 * @param runtime {@code yukta.plugins.container.runtime}: the CLI, a name on {@code PATH} or a path
 * @param args {@code yukta.plugins.container.args.<TYPE>}: arguments added to {@code run}, such as
 *     {@code --network=none} or {@code --memory=256m}
 */
@Slf4j
@SuppressWarnings("PMD.UseConcurrentHashMap") // built once, then read only
public record Containers(String runtime, Map<String, List<String>> args) {

  /**
   * Variables the CLI reads to reach its daemon. They reach the CLI only; {@code run} passes no
   * variable into the container.
   */
  // ponytail: fixed list; make it a setting when a runtime needs another variable.
  private static final List<String> CLIENT_ENV =
      List.of(
          "HOME",
          "DOCKER_HOST",
          "DOCKER_CONFIG",
          "DOCKER_CONTEXT",
          "CONTAINER_HOST",
          "XDG_RUNTIME_DIR");

  /** Longest a pull at startup may take. */
  private static final long PULL_SECONDS = 600;

  /** Longest an inspect or a remove may take. */
  private static final long CLI_SECONDS = 30;

  /**
   * Copies the map with its keys normalized: an environment variable such as {@code
   * YUKTA_PLUGINS_CONTAINER_ARGS_WORD_COUNT} binds as {@code word.count}, so keys match a type
   * ignoring case and separators.
   */
  public Containers {
    final Map<String, List<String>> byKey = new HashMap<>();
    args.forEach((type, list) -> byKey.put(key(type), List.copyOf(list)));
    args = Map.copyOf(byKey);
  }

  private static String key(final String type) {
    return type.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
  }

  /**
   * A fresh container name for one message, so a container left by a killed client can be removed.
   *
   * @param type the plugin type
   * @return {@code yukta-<type>-<uuid>}
   */
  /* default */ static String name(final String type) {
    return "yukta-" + type.toLowerCase(Locale.ROOT).replace('_', '-') + "-" + UUID.randomUUID();
  }

  /**
   * The command that runs one message in a new container: no mounts, not privileged, no variables,
   * network on, unless the operator's arguments say otherwise.
   *
   * @param manifest the plugin
   * @param name the container's name
   * @return the argv
   */
  /* default */ List<String> run(final PluginManifest manifest, final String name) {
    final List<String> argv =
        new ArrayList<>(List.of(runtime, "run", "--rm", "-i", "--name", name));
    argv.addAll(args.getOrDefault(key(manifest.type()), List.of()));
    argv.add(manifest.image());
    return argv;
  }

  /**
   * The runtime's own variables, for every call of its CLI.
   *
   * @return the variables of {@link #CLIENT_ENV} the server has
   */
  /* default */ static Map<String, String> clientEnv() {
    final Map<String, String> env = new HashMap<>();
    for (final String name : CLIENT_ENV) {
      final String value = System.getenv(name);
      if (value != null) {
        env.put(name, value);
      }
    }
    return env;
  }

  /**
   * Makes sure the plugin's image is present, pulling it when it is not; images are never pulled
   * per message.
   *
   * @param manifest the plugin
   * @param gateway runs the CLI
   * @throws IllegalStateException when the image cannot be pulled
   */
  /* default */ void ensureImage(
      final PluginManifest manifest, final ProcessExecutorGateway gateway) {
    if (call(gateway, List.of(runtime, "image", "inspect", manifest.image()), CLI_SECONDS)
        .isSuccess()) {
      return;
    }
    log.atInfo().log("Plugin {}: pulling {}", manifest.type(), manifest.image());
    final ProcessExecutionResult pull =
        call(gateway, List.of(runtime, "pull", manifest.image()), PULL_SECONDS);
    if (!pull.isSuccess()) {
      throw new IllegalStateException(
          "Plugin "
              + manifest.type()
              + ": cannot pull "
              + manifest.image()
              + " with "
              + runtime
              + ": "
              + pull.stderr().strip());
    }
  }

  /**
   * Removes a container whose client was stopped (a timeout or a cancel), which can leave it
   * running. Failures are logged; a container that is already gone is not one.
   *
   * @param name the container's name
   * @param gateway runs the CLI
   * @return completes when the CLI exits
   */
  /* default */ Mono<Void> remove(final String name, final ProcessExecutorGateway gateway) {
    return gateway
        .executeInstalled(spec(List.of(runtime, "rm", "-f", name), CLI_SECONDS), PluginLogSink.NOOP)
        .doOnError(e -> log.atWarn().log("Cannot remove container {}: {}", name, e.getMessage()))
        .onErrorComplete()
        .then();
  }

  private static ProcessExecutionResult call(
      final ProcessExecutorGateway gateway, final List<String> argv, final long seconds) {
    return gateway
        .executeInstalled(spec(argv, seconds), PluginLogSink.NOOP)
        .blockOptional()
        .orElseThrow();
  }

  private static ProcessExecutionSpec spec(final List<String> argv, final long seconds) {
    return ProcessExecutionSpec.builder()
        .command(argv)
        .timeoutSeconds(seconds)
        .env(clientEnv())
        .captureStderr(true)
        .build();
  }
}
