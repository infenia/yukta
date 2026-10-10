// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.file;

import com.infenia.yukta.message.Message;
import com.infenia.yukta.plugin.core.PluginDescriptor;
import com.infenia.yukta.plugin.type.ProcessorPlugin;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Writes (or appends) the payload's text to a file inside a sandbox directory. */
@Component
public final class FileWritePlugin implements ProcessorPlugin {

  /** Default constructor. */
  public FileWritePlugin() {
    super();
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
        Optional.empty());
  }

  @Override
  public String getType() {
    return "FILE_WRITE";
  }

  @Override
  public Mono<Void> validateConfig(final Map<String, Object> config) {
    return Mono.fromRunnable(() -> FileSandbox.baseDir(config));
  }

  @Override
  public Flux<Message<?>> process(final Flux<Message<?>> input, final Map<String, Object> config) {
    final boolean append = Boolean.TRUE.equals(config.get("append"));
    final String field = String.valueOf(config.getOrDefault("contentField", "content"));
    return input.concatMap(
        message ->
            Mono.fromCallable(
                    () -> {
                      final var file = FileSandbox.resolve(config, message.payload());
                      final Object content =
                          message.payload() instanceof Map<?, ?> map
                              ? map.get(field)
                              : message.payload();
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
                    })
                .subscribeOn(Schedulers.boundedElastic()));
  }
}
