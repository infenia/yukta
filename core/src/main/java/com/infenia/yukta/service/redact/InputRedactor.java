// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.redact;

import com.infenia.yukta.config.YuktaProperties;
import com.infenia.yukta.plugin.input.InputRequestSpec;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Removes secrets from the prompt and the context of an input request before the request is stored,
 * logged or shown. Both come from a plugin, often an AI agent, so they may hold anything the agent
 * read.
 *
 * <p>Besides the shared secret patterns ({@link SecretRedactor}), it replaces {@code ${env.NAME}}
 * references, and the value of a context entry whose key is one of {@code yukta.input.redact-keys}.
 * The patterns are patterns, not a guarantee: a secret in an unusual shape gets through.
 */
@Component
public class InputRedactor {

  /** An environment reference; the reference is replaced, whatever it would resolve to. */
  private static final String ENV_REFERENCE = "\\$\\{env\\.[^}]*\\}";

  /** Replaces the secrets. */
  private final SecretRedactor redactor;

  /**
   * Creates the redactor.
   *
   * @param properties the {@code yukta.input} settings; {@code redact-keys} are the extra names
   */
  @Autowired
  public InputRedactor(final YuktaProperties properties) {
    this(properties.input().redactKeys());
  }

  /**
   * Creates the redactor with explicit key names.
   *
   * @param redactKeys context keys whose value is a secret, besides the built-in ones
   */
  public InputRedactor(final List<String> redactKeys) {
    final List<String> patterns = new ArrayList<>(SecretRedactor.DEFAULT_PATTERNS);
    patterns.add(ENV_REFERENCE);
    this.redactor = new SecretRedactor(patterns, redactKeys);
  }

  /**
   * Redacts a text.
   *
   * @param text the text
   * @return the text without secrets
   */
  public String text(final String text) {
    return redactor.redactText(text);
  }

  /**
   * Redacts the prompt and the context of a spec.
   *
   * @param spec the spec as the plugin built it
   * @return a spec with the same fields, the prompt and context cleaned
   */
  public InputRequestSpec redact(final InputRequestSpec spec) {
    return new InputRequestSpec(
        spec.key(),
        spec.kind(),
        redactor.redactText(spec.prompt()),
        redactor.redactMap(spec.context()),
        spec.options(),
        spec.responseSchema(),
        spec.risk(),
        spec.audience(),
        spec.ttl(),
        spec.onTimeout(),
        spec.defaultDecision(),
        spec.routeName(),
        spec.requester());
  }
}
