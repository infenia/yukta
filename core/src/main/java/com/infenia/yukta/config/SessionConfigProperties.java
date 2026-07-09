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

  /** Subdirectory for file modification logs. */
  private String fileLogSubDir = "modified-files";

  /** Subdirectory for execution results. */
  private String resultLogSubDir = "results";

  /** Execution timeout in seconds. */
  private Long executionTimeoutSeconds = 3600L;

  /** Type of session store (e.g., in-memory). */
  private String storeType = "in-memory";
}
