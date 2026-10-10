// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.file;

import com.infenia.yukta.message.Message;
import com.infenia.yukta.plugin.core.PluginDescriptor;
import com.infenia.yukta.plugin.type.ProcessorPlugin;
import java.nio.file.Files;
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
        Optional.empty());
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
