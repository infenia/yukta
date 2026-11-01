// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.input;

import com.infenia.yukta.plugin.input.InputGateway;
import com.infenia.yukta.plugin.input.InputIdentity;
import com.infenia.yukta.plugin.input.InputRequestSpec;
import com.infenia.yukta.plugin.input.InputResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * The gateway a plugin takes through an {@code ObjectProvider<InputGateway>}. One singleton serves
 * every node: the run and node that ask are read from the Reactor context at subscription, so a
 * plugin cannot ask in the name of another node.
 */
@Component
@RequiredArgsConstructor
public class DefaultInputGateway implements InputGateway {

  /** Owns the requests. */
  private final InputBroker broker;

  @Override
  public Mono<InputResponse> ask(final InputRequestSpec spec) {
    return Mono.deferContextual(context -> broker.ask(InputIdentity.from(context), spec));
  }
}
