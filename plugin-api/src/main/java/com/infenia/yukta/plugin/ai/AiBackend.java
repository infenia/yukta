// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.ai;

import com.infenia.yukta.plugin.core.ConfigField;
import java.util.List;

/**
 * A way to reach a model or an agent. A plugin selects one by its {@link #kind()}; the list of
 * backends, with their options, is served at {@code GET /api/ai/backends} and by the {@code
 * list_ai_backends} MCP tool, so a client can configure one without other documentation.
 */
public interface AiBackend {

  /**
   * The value a plugin's {@code backend} option takes to select this backend.
   *
   * @return the kind, e.g. {@code SYSTEMONE}
   */
  String kind();

  /**
   * What the backend is and when to pick it.
   *
   * @return a short description
   */
  String description();

  /**
   * The options this backend reads from a node's config, with defaults and descriptions.
   *
   * @return the options
   */
  List<ConfigField> options();

  /**
   * The backend as data, for the API.
   *
   * @return its kind, description and options
   */
  default Info info() {
    return new Info(kind(), description(), options());
  }

  /**
   * A backend as the API shows it.
   *
   * @param kind the value of the {@code backend} option
   * @param description what it is and when to pick it
   * @param options the options it reads
   */
  record Info(String kind, String description, List<ConfigField> options) {

    /** Compact constructor to ensure immutability. */
    public Info {
      options = options == null ? List.of() : List.copyOf(options);
    }
  }
}
