// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.controlplane.composite;

import com.infenia.yukta.controlplane.model.ModelPlaneSettings;
import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings of the rules-first control plane, bound from {@code yukta.control-plane.rules-first.*}:
 * the rules file and, below {@code model}, everything {@link ModelPlaneSettings} takes (model name,
 * prompt, options, events).
 *
 * @param rulesFile the JSON rules file
 * @param model the model asked when no rule fires
 */
@ConfigurationProperties("yukta.control-plane.rules-first")
public record RulesFirstSettings(Path rulesFile, ModelPlaneSettings model) {}
