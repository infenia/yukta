// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.external;

import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.message.Message;
import com.infenia.yukta.plugin.mcp.McpCallResult;
import com.infenia.yukta.plugin.util.VariableResolver;
import java.util.Map;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * One MCP tool call per message: the node's declared configuration, resolved for the message, is
 * the tool's arguments. The output payload is the tool's structured result, or its text when it has
 * none; a tool error fails the message.
 *
 * @param manifest the plugin
 * @param mcp the server
 * @param resolver resolves the configuration per message
 */
public record McpRun(PluginManifest manifest, McpConnection mcp, VariableResolver resolver)
    implements PluginRun {

  @Override
  public Flux<Message<?>> outputs(final Message<?> message, final Map<String, Object> config) {
    return call(message, config)
        .<Message<?>>map(
            result ->
                DefaultMessage.from(
                        message, result.structured() != null ? result.structured() : result.text())
                    .withSourcePort(manifest.ports().get(0)))
        .flux();
  }

  @Override
  public Mono<Void> consume(final Message<?> message, final Map<String, Object> config) {
    return call(message, config).then();
  }

  private Mono<McpCallResult> call(final Message<?> message, final Map<String, Object> config) {
    final String tool = (String) manifest.mcp().get("tool");
    return manifest
        .resolve(resolver, message, config)
        .flatMap(arguments -> mcp.call(tool, arguments))
        .flatMap(
            result ->
                result.isError()
                    ? Mono.error(
                        new IllegalStateException(
                            "Plugin "
                                + manifest.type()
                                + ": tool "
                                + tool
                                + " failed: "
                                + result.error()))
                    : Mono.just(result));
  }
}
