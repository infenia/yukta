// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.config;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Configuration properties for Yukta. */
@ConfigurationProperties(prefix = "yukta")
@Validated
@Data
@NoArgsConstructor
public class YuktaProperties {

  /** Heartbeat interval for nodes in seconds. */
  private Long heartbeatIntervalSeconds = 10L;
}
