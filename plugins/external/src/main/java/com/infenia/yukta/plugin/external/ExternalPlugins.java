// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.external;

import com.infenia.yukta.plugin.core.Plugin;
import com.infenia.yukta.plugin.core.PluginCategory;
import com.infenia.yukta.plugin.core.PluginSource;
import com.infenia.yukta.plugin.mcp.McpClientGateway;
import com.infenia.yukta.plugin.process.ProcessExecutorGateway;
import com.infenia.yukta.plugin.util.VariableResolver;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Community plugins: every subdirectory of {@code yukta.plugins.dir} with a {@code
 * yukta-plugin.json} whose type the operator named in {@code yukta.plugins.enabled} ({@code TYPE},
 * or {@code TYPE@sha256:<digest>} to pin the directory's contents). {@code all} does not enable
 * them, so placing a directory never runs code on its own. An enabled plugin that is invalid fails
 * startup; one that is not enabled is only logged.
 */
@Slf4j
@SuppressWarnings({
  "PMD.UseConcurrentHashMap", // filled once at startup, on one thread
  "PMD.TooManyMethods" // the loader: read, pin, connect, close
})
public final class ExternalPlugins implements PluginSource, AutoCloseable {

  private static final String PREFIX = "Plugin ";

  /** The manifest's file name in a plugin directory. */
  public static final String MANIFEST = "yukta-plugin.json";

  private final @Nullable Path dir;
  private final Map<String, String> enabled;
  private final ProcessExecutorGateway gateway;
  private final VariableResolver resolver;
  private final CommandRun.OutputLimits limits;
  private final Containers containers;
  private final McpClientGateway mcpGateway;
  private final Map<String, McpConnection> connections = new ConcurrentHashMap<>();
  private final JsonMapper json = JsonMapper.builder().build();
  private final WireSchemas schemas = new WireSchemas(json);

  /**
   * Creates the source.
   *
   * @param dir the plugins directory, or null for none (no plugins)
   * @param enabled {@code yukta.plugins.enabled}: types, some pinned with {@code @sha256:...}
   * @param gateway runs the commands
   * @param resolver resolves the node configuration per message
   * @param maxOutputBytes the most a plugin may write to stdout for one message
   * @param maxOutputs the most output objects a plugin may write for one message
   * @param containers runs {@code run.image} plugins
   * @param mcpGateway reaches {@code run.mcp} plugins over HTTP
   */
  public ExternalPlugins(
      final @Nullable Path dir,
      final List<String> enabled,
      final ProcessExecutorGateway gateway,
      final VariableResolver resolver,
      final long maxOutputBytes,
      final int maxOutputs,
      final Containers containers,
      final McpClientGateway mcpGateway) {
    this.dir = dir;
    this.enabled = pins(enabled);
    this.gateway = gateway;
    this.resolver = resolver;
    this.limits = new CommandRun.OutputLimits(maxOutputBytes, maxOutputs);
    this.containers = containers;
    this.mcpGateway = mcpGateway;
  }

  /** Type to pinned digest ({@code ""} when not pinned). */
  private static Map<String, String> pins(final List<String> enabled) {
    final Map<String, String> pins = new HashMap<>();
    for (final String entry : enabled) {
      final int at = entry.indexOf('@');
      pins.put(at < 0 ? entry : entry.substring(0, at), at < 0 ? "" : entry.substring(at + 1));
    }
    return pins;
  }

  @Override
  public List<Plugin> plugins() {
    if (dir == null) {
      return List.of();
    }
    if (!Files.isDirectory(dir)) {
      throw new IllegalStateException("yukta.plugins.dir " + dir + " is not a directory");
    }
    if (Files.isWritable(dir)) {
      log.atWarn()
          .log(
              "Plugins directory {} is writable by the server's user: whoever can write there can"
                  + " change what an enabled plugin runs",
              dir);
    }
    final Map<String, PluginManifest> found = new HashMap<>();
    for (final Path pluginDir : subdirectories(dir)) {
      final PluginManifest manifest = read(pluginDir);
      if (manifest != null) {
        final PluginManifest twin = found.putIfAbsent(manifest.type(), manifest);
        if (twin != null) {
          throw new IllegalStateException(
              PREFIX
                  + "type "
                  + manifest.type()
                  + " is in both "
                  + twin.dir()
                  + " and "
                  + pluginDir);
        }
      }
    }
    enabled.forEach(
        (type, pin) -> {
          if (!pin.isEmpty() && !found.containsKey(type)) {
            throw new IllegalStateException(
                type + "@" + pin + " is enabled but no plugin in " + dir + " has that type");
          }
        });
    return found.values().stream().map(this::plugin).toList();
  }

  private static List<Path> subdirectories(final Path root) {
    try (Stream<Path> list = Files.list(root)) {
      return list.filter(p -> Files.isRegularFile(p.resolve(MANIFEST))).sorted().toList();
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** The manifest of an enabled plugin; null when the plugin is not enabled. */
  @SuppressWarnings("unchecked")
  private @Nullable PluginManifest read(final Path pluginDir) {
    final Object doc;
    try {
      doc = json.readValue(Files.readString(pluginDir.resolve(MANIFEST)), Object.class);
    } catch (final IOException | JacksonException e) {
      throw new IllegalStateException("Cannot read " + pluginDir.resolve(MANIFEST), e);
    }
    final Object type = doc instanceof Map<?, ?> m ? m.get("type") : null;
    if (!(type instanceof String name) || !enabled.containsKey(name)) {
      log.atInfo().log(
          "Plugin {} in {} is not enabled (list its type in yukta.plugins.enabled)",
          type,
          pluginDir);
      return null;
    }
    final String refusal = schemas.manifestRefusal(doc);
    if (refusal != null) {
      throw new IllegalStateException(
          PREFIX + name + ": " + pluginDir.resolve(MANIFEST) + " is invalid: " + refusal);
    }
    final PluginManifest manifest =
        PluginManifest.from((Map<String, Object>) doc, pluginDir.toAbsolutePath().normalize());
    return runnable(manifest);
  }

  /**
   * The manifest ready to run, its pin checked: a local executable resolved, an image present, or
   * the MCP tool found and its form read from the tool's schema.
   */
  private PluginManifest runnable(final PluginManifest manifest) {
    return switch (manifest.runForm()) {
      case "command" -> {
        requirePin(manifest, PluginDigest.compute(manifest.dir()));
        yield withLocalExecutable(manifest);
      }
      case "image" -> {
        requirePin(manifest, PluginDigest.compute(manifest.dir()));
        containers.ensureImage(manifest, gateway);
        yield manifest;
      }
      default -> withTool(manifest);
    };
  }

  /**
   * Connects to the plugin's MCP server and reads its tool. The pin covers the directory and the
   * tool's input schema, whose properties become the configuration form.
   */
  @SuppressWarnings({"unchecked", "PMD.CloseResource"}) // closed by close()
  private PluginManifest withTool(final PluginManifest manifest) {
    final String dirDigest = PluginDigest.compute(manifest.dir());
    final McpConnection mcp = connect(manifest);
    connections.put(manifest.type(), mcp);
    final Map<String, Object> tool =
        mcp.tool((String) manifest.mcp().get("tool")).blockOptional().orElseThrow();
    final Map<String, Object> schema =
        (Map<String, Object>) tool.getOrDefault("inputSchema", Map.of());
    requirePin(manifest, McpTools.digest(dirDigest, schema));
    return manifest.withConfig(McpTools.fields(manifest.config(), schema));
  }

  @SuppressWarnings("unchecked")
  private McpConnection connect(final PluginManifest manifest) {
    final Map<String, Object> run = manifest.mcp();
    if (run.get("url") instanceof String url) {
      return new HttpMcp(manifest.type(), url, manifest.timeout(), mcpGateway);
    }
    if (run.get("image") instanceof String image) {
      final PluginManifest withImage = manifest.withImage(image);
      containers.ensureImage(withImage, gateway);
      final String name = Containers.name(manifest.type());
      return new StdioMcp(
          manifest.type(),
          containers.run(withImage, name),
          Containers.clientEnv(),
          manifest.dir(),
          manifest.timeout(),
          gateway,
          json,
          () -> containers.remove(name, gateway).subscribe());
    }
    final PluginManifest local =
        withLocalExecutable(manifest.withCommand((List<String>) run.get("command")));
    return new StdioMcp(
        manifest.type(),
        local.command(),
        Map.of(),
        manifest.dir(),
        manifest.timeout(),
        gateway,
        json,
        () -> {});
  }

  /** Stops the MCP servers the plugins started. */
  @Override
  public void close() {
    connections.values().forEach(McpConnection::close);
  }

  /** Checks the directory against the digest pinned in the enable list, if any, and logs it. */
  private void requirePin(final PluginManifest manifest, final String digest) {
    final String pin = enabled.get(manifest.type());
    if (!pin.isEmpty() && !pin.equals(digest)) {
      throw new IllegalStateException(
          PREFIX
              + manifest.type()
              + " in "
              + manifest.dir()
              + " has digest "
              + digest
              + ", pinned "
              + pin);
    }
    log.atInfo().log("Plugin {} from {}, {}", manifest.type(), manifest.dir(), digest);
  }

  /**
   * Resolves a command path such as {@code ./run} or {@code bin/run} inside the plugin directory,
   * and refuses one that leaves it; a bare name ({@code python3}) is looked up on {@code PATH}.
   */
  private static PluginManifest withLocalExecutable(final PluginManifest manifest) {
    final List<String> argv = manifest.command();
    final String executable = argv.get(0);
    if (!executable.contains("/")) {
      return manifest;
    }
    final Path path = manifest.dir().resolve(executable).normalize();
    if (!path.startsWith(manifest.dir()) || !Files.isRegularFile(path)) {
      throw new IllegalStateException(
          PREFIX + manifest.type() + ": " + executable + " is not a file inside " + manifest.dir());
    }
    final List<String> command = new ArrayList<>(argv);
    command.set(0, path.toString());
    return manifest.withCommand(List.copyOf(command));
  }

  private Plugin plugin(final PluginManifest manifest) {
    final PluginRun run =
        "mcp".equals(manifest.runForm())
            ? new McpRun(manifest, connections.get(manifest.type()), resolver)
            : new CommandRun(manifest, gateway, resolver, json, schemas, limits, containers);
    return manifest.category() == PluginCategory.TERMINAL
        ? new ExternalTerminalPlugin(manifest, run)
        : new ExternalProcessorPlugin(manifest, run);
  }

  @Override
  public String toString() {
    return "yukta.plugins.dir " + dir;
  }
}
