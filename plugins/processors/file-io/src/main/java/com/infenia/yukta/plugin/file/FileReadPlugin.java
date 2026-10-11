// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.file;

import com.infenia.yukta.message.Message;
import com.infenia.yukta.plugin.core.ConfigField;
import com.infenia.yukta.plugin.core.FailureMode;
import com.infenia.yukta.plugin.core.PluginDescriptor;
import com.infenia.yukta.plugin.core.PluginMetricsReporter;
import com.infenia.yukta.plugin.core.UiDesign;
import com.infenia.yukta.plugin.util.ConfigValidation;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Reads a UTF-8 text file inside a sandbox directory and emits {@code {path, content}}. */
@Component
public final class FileReadPlugin extends AbstractFilePlugin {

  /** Largest file read unless {@code maxBytes} says otherwise (10 MiB). */
  private static final long DEFAULT_MAX_BYTES = 10L * 1024 * 1024;

  /** Default constructor without metrics. */
  public FileReadPlugin() {
    super(PluginMetricsReporter.NOOP);
  }

  /**
   * Spring constructor.
   *
   * @param reporterProvider optional metrics reporter
   */
  @Autowired
  public FileReadPlugin(final ObjectProvider<PluginMetricsReporter> reporterProvider) {
    super(reporterProvider.getIfAvailable(() -> PluginMetricsReporter.NOOP));
  }

  @Override
  public PluginDescriptor describe() {
    return new PluginDescriptor(
        "Reads a UTF-8 text file from a sandbox directory.",
        "Configure with:\n"
            + "- baseDir: sandbox directory (required); paths outside it are rejected.\n"
            + "- path: file relative to baseDir; defaults to the payload's 'path' field.\n"
            + "- maxBytes: largest readable file (default 10 MiB).\n"
            + "- failureMode: ERROR (default) or CONTINUE (routes to the error port).\n"
            + "Emits {path, content}.",
        Optional.of(
            new UiDesign(
                """
                <div class="flex items-center w-full h-full bg-slate-50 border-slate-200 border-2 rounded-xl px-4 gap-3">
                    <div class="flex-shrink-0 w-8 h-8 bg-blue-100 text-blue-500 rounded-lg flex items-center justify-center">
                        <svg class="w-5 h-5" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" viewBox="0 0 24 24"><path d="M14 3H7a2 2 0 00-2 2v14a2 2 0 002 2h10a2 2 0 002-2V8zM14 3v5h5M9 13h6M9 17h6"/></svg>
                    </div>
                    <div class="flex flex-col min-w-0">
                        <div class="text-[10px] text-slate-400 font-bold uppercase tracking-wider leading-none mb-1">File Read</div>
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
            "baseDir", ConfigField.Type.STRING, "Sandbox directory; paths outside it are rejected"),
        ConfigField.optional(
            "path",
            ConfigField.Type.STRING,
            null,
            "File relative to baseDir (default: payload path)"),
        ConfigField.optional(
            "maxBytes", ConfigField.Type.NUMBER, DEFAULT_MAX_BYTES, "Largest file read, in bytes"),
        failureModeField());
  }

  @Override
  public String getType() {
    return "FILE_READ";
  }

  @Override
  public Mono<Void> validateConfig(final Map<String, Object> config) {
    return Mono.fromRunnable(
        () -> {
          FileSandbox.baseDir(config);
          ConfigValidation.positiveLong(config, "maxBytes", DEFAULT_MAX_BYTES);
          FailureMode.from(config.get(FAILURE_MODE));
        });
  }

  @Override
  public Flux<Message<?>> process(final Flux<Message<?>> input, final Map<String, Object> config) {
    final long maxBytes = ConfigValidation.positiveLong(config, "maxBytes", DEFAULT_MAX_BYTES);
    return perMessage(
        input,
        config,
        "file_read",
        message -> {
          final var file = FileSandbox.resolve(config, message.payload());
          return message
              .withPayload(Map.of("path", file.toString(), "content", readBounded(file, maxBytes)))
              .withSourcePort("default");
        });
  }

  /** Reads at most maxBytes + 1 bytes, so a file that grows mid-read still can't exceed the cap. */
  private static String readBounded(final Path file, final long maxBytes) throws IOException {
    try (InputStream stream = Files.newInputStream(file)) {
      final byte[] bytes = stream.readNBytes((int) Math.min(maxBytes + 1, Integer.MAX_VALUE - 8));
      if (bytes.length > maxBytes) {
        throw new IllegalArgumentException("file larger than maxBytes (" + maxBytes + "): " + file);
      }
      return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
    }
  }
}
