// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.file;

import com.infenia.yukta.message.Message;
import com.infenia.yukta.plugin.core.ConfigField;
import com.infenia.yukta.plugin.core.FailureMode;
import com.infenia.yukta.plugin.core.PluginDescriptor;
import com.infenia.yukta.plugin.core.PluginMetricsReporter;
import com.infenia.yukta.plugin.core.UiDesign;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Writes (or appends) the payload's text to a file inside a sandbox directory. */
@Component
public final class FileWritePlugin extends AbstractFilePlugin {

  /** Default constructor without metrics. */
  public FileWritePlugin() {
    super(PluginMetricsReporter.NOOP);
  }

  /**
   * Spring constructor.
   *
   * @param reporterProvider optional metrics reporter
   */
  @Autowired
  public FileWritePlugin(final ObjectProvider<PluginMetricsReporter> reporterProvider) {
    super(reporterProvider.getIfAvailable(() -> PluginMetricsReporter.NOOP));
  }

  @Override
  public PluginDescriptor describe() {
    return new PluginDescriptor(
        "Writes or appends text to a file in a sandbox directory; passes the message through.",
        "Configure with:\n"
            + "- baseDir: sandbox directory (required); paths outside it are rejected.\n"
            + "- path: file relative to baseDir; defaults to the payload's 'path' field.\n"
            + "- contentField: payload field holding the text (default 'content'); a plain String"
            + " payload is written as is.\n"
            + "- append: true to append a line instead of overwriting (default false).",
        Optional.of(
            new UiDesign(
                """
                <div class="flex items-center w-full h-full bg-slate-50 border-slate-200 border-2 rounded-xl px-4 gap-3">
                    <div class="flex-shrink-0 w-8 h-8 bg-blue-100 text-blue-500 rounded-lg flex items-center justify-center">
                        <svg class="w-5 h-5" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" viewBox="0 0 24 24"><path d="M14 3H7a2 2 0 00-2 2v14a2 2 0 002 2h10a2 2 0 002-2V8zM14 3v5h5M9 13h6M9 17h6"/></svg>
                    </div>
                    <div class="flex flex-col min-w-0">
                        <div class="text-[10px] text-slate-400 font-bold uppercase tracking-wider leading-none mb-1">File Write</div>
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
            "contentField", ConfigField.Type.STRING, "content", "Payload field holding the text"),
        ConfigField.optional(
            "append", ConfigField.Type.BOOLEAN, false, "Append a line instead of overwriting"),
        failureModeField());
  }

  @Override
  public String getType() {
    return "FILE_WRITE";
  }

  @Override
  public Mono<Void> validateConfig(final Map<String, Object> config) {
    return Mono.fromRunnable(
        () -> {
          FileSandbox.baseDir(config);
          FailureMode.from(config.get(FAILURE_MODE));
        });
  }

  @Override
  public Flux<Message<?>> process(final Flux<Message<?>> input, final Map<String, Object> config) {
    final boolean append = Boolean.TRUE.equals(config.get("append"));
    final String field = String.valueOf(config.getOrDefault("contentField", "content"));
    return perMessage(
        input,
        config,
        "file_write",
        message -> {
          final var file = FileSandbox.resolve(config, message.payload());
          final Object content =
              message.payload() instanceof Map<?, ?> map ? map.get(field) : message.payload();
          if (content == null) {
            throw new IllegalArgumentException("no content in payload." + field);
          }
          Files.createDirectories(java.util.Objects.requireNonNull(file.getParent()));
          if (append) {
            Files.writeString(
                file,
                content + System.lineSeparator(),
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
          } else {
            Files.writeString(file, String.valueOf(content));
          }
          return message.withSourcePort("default");
        });
  }
}
