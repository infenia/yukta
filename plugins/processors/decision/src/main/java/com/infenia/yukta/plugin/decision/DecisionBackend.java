// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.decision;

import com.infenia.yukta.plugin.ai.AiBackend;

/** A backend that answers decision questions; {@link DecisionModels} picks one by its kind. */
public interface DecisionBackend extends AiBackend, DecisionModel {}
