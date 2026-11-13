// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.external;

import com.infenia.yukta.message.Message;
import com.infenia.yukta.plugin.type.ProcessorPlugin;
import java.util.Map;
import reactor.core.publisher.Flux;

/** A community processor: one run of its command per message, in order. */
public final class ExternalProcessorPlugin extends ExternalPlugin implements ProcessorPlugin {

  /* default */ ExternalProcessorPlugin(final PluginManifest manifest, final CommandRun run) {
    super(manifest, run);
  }

  @Override
  public Flux<Message<?>> process(final Flux<Message<?>> input, final Map<String, Object> config) {
    return input.concatMap(message -> run.outputs(message, config));
  }
}
