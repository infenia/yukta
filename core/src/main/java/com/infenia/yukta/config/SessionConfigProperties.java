/*
 * Copyright 2026 Infenia Private Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
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
