// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.external;

import com.infenia.yukta.message.Message;
import com.infenia.yukta.plugin.type.TerminalPlugin;
import java.util.Map;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** A community terminal: one run of its command per message, in order; its stdout is ignored. */
public final class ExternalTerminalPlugin extends ExternalPlugin implements TerminalPlugin {

  /* default */ ExternalTerminalPlugin(final PluginManifest manifest, final CommandRun run) {
    super(manifest, run);
  }

  @Override
  public Mono<Void> consume(final Flux<Message<?>> input, final Map<String, Object> config) {
    return input.concatMap(message -> run.consume(message, config)).then();
  }
}
