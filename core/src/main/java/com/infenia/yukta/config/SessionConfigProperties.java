// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.config;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Configuration properties for session management. */
@ConfigurationProperties(prefix = "yukta.session")
@Validated
@Data
@NoArgsConstructor
public class SessionConfigProperties {

  /** Base directory for session storage. */
  private String baseDir = System.getProperty("user.home") + "/.yukta";
}
