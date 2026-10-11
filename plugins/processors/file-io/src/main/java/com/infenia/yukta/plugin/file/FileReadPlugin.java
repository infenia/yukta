// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.file;

import com.infenia.yukta.message.Message;
import com.infenia.yukta.plugin.core.ConfigField;
import com.infenia.yukta.plugin.core.PluginDescriptor;
import com.infenia.yukta.plugin.core.UiDesign;
import com.infenia.yukta.plugin.type.ProcessorPlugin;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Reads a UTF-8 text file inside a sandbox directory and emits {@code {path, content}}. */
@Component
public final class FileReadPlugin implements ProcessorPlugin {

  /** Default constructor. */
  public FileReadPlugin() {
    super();
  }

  @Override
  public PluginDescriptor describe() {
    return new PluginDescriptor(
        "Reads a UTF-8 text file from a sandbox directory.",
        "Configure with:\n"
            + "- baseDir: sandbox directory (required); paths outside it are rejected.\n"
            + "- path: file relative to baseDir; defaults to the payload's 'path' field.\n"
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
            "File relative to baseDir (default: payload path)"));
  }

  @Override
  public String getType() {
    return "FILE_READ";
  }

  @Override
  public Mono<Void> validateConfig(final Map<String, Object> config) {
    return Mono.fromRunnable(() -> FileSandbox.baseDir(config));
  }

  @Override
  public Flux<Message<?>> process(final Flux<Message<?>> input, final Map<String, Object> config) {
    return input.concatMap(
        message ->
            Mono.fromCallable(
                    () -> {
                      final var file = FileSandbox.resolve(config, message.payload());
                      final String content = Files.readString(file);
                      return (Message<?>)
                          message
                              .withPayload(Map.of("path", file.toString(), "content", content))
                              .withSourcePort("default");
                    })
                .subscribeOn(Schedulers.boundedElastic()));
  }
}
