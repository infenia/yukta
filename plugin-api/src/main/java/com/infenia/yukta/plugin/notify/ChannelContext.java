// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.notify;

import com.infenia.yukta.plugin.input.InputRequest;
import java.time.Instant;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * What a channel needs besides the notification.
 *
 * @param name the configured target, such as {@code ops} for {@code webhook:ops}
 * @param settings the settings of the target, merged with those of the tier that named it
 * @param request the redacted input request of an action, or {@code null}
 * @param answerToken a one-time token the receiver may answer with; only when a route tier names
 *     this channel as a responder, never for a rule's channel
 * @param answerExpires when the token expires, or {@code null} without a token
 * @since 0.1.0-alpha.5
 */
public record ChannelContext(
    String name,
    Map<String, Object> settings,
    @Nullable InputRequest request,
    @Nullable String answerToken,
    @Nullable Instant answerExpires) {

  /** Keeps an immutable copy of the settings. */
  public ChannelContext {
    settings = Map.copyOf(settings);
  }
}
