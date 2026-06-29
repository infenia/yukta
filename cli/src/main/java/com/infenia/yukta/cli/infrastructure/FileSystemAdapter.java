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
package com.infenia.yukta.cli.infrastructure;

import java.io.IOException;
import java.nio.file.OpenOption;
import java.nio.file.Path;

/** Adapter interface for file system operations. */
public interface FileSystemAdapter {
  /**
   * Creates directories including all parent directories.
   *
   * @param path the path to create
   */
  void createDirectories(Path path) throws IOException;

  /**
   * Writes a string to a file with the given options.
   *
   * @param path the file path
   * @param content the string content
   * @param options open options (e.g., CREATE, TRUNCATE_EXISTING)
   */
  void writeString(Path path, String content, OpenOption... options) throws IOException;

  /**
   * Reads a string from a file.
   *
   * @param path the file path
   * @return the file content
   */
  String readString(Path path) throws IOException;

  /**
   * Deletes a file.
   *
   * @param path the file path
   */
  void delete(Path path) throws IOException;

  /**
   * Deletes a file if it exists.
   *
   * @param path the file path
   * @return true if the file was deleted, false if it didn't exist
   */
  boolean deleteIfExists(Path path) throws IOException;

  /**
   * Checks if a file exists.
   *
   * @param path the file path
   * @return true if the file exists
   */
  boolean exists(Path path);
}
