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
package com.infenia.yukta.cli;

import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Utility class for formatting output in CLI commands. */
@Component
public class CliFormatter {

  /** JSON object mapper for serialization. */
  private final ObjectMapper objectMapper;

  /**
   * Constructs a CLI formatter with an ObjectMapper.
   *
   * @param objectMapper the JSON object mapper
   */
  public CliFormatter(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  /**
   * Formats an object as pretty-printed JSON.
   *
   * @param value the object to format
   * @return the JSON string
   * @throws Exception if serialization fails
   */
  public String formatAsJson(Object value) throws Exception {
    return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
  }

  /**
   * Prints table rows to standard output.
   *
   * @param items the items to print
   */
  public void printTable(List<String> items) {
    items.forEach(System.out::println);
  }

  /**
   * Prints an object as JSON to standard output.
   *
   * @param value the object to print
   * @throws Exception if serialization fails
   */
  public void printJson(Object value) throws Exception {
    System.out.println(formatAsJson(value));
  }
}
