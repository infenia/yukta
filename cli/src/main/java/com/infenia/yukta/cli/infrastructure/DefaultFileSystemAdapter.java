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

import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import org.springframework.stereotype.Component;

/** Default implementation of file system adapter using Java NIO. */
@Component
public class DefaultFileSystemAdapter implements FileSystemAdapter {

  /**
   * Creates directories for the given path, including all parent directories.
   *
   * @param path the path to create
   * @throws Exception if creation fails
   */
  @Override
  public void createDirectories(Path path) throws Exception {
    Files.createDirectories(path);
  }

  /**
   * Writes a string to a file with the specified options.
   *
   * @param path the file path
   * @param content the string content to write
   * @param options open options
   * @throws Exception if writing fails
   */
  @Override
  public void writeString(Path path, String content, OpenOption... options) throws Exception {
    Files.writeString(path, content, options);
  }

  /**
   * Reads the entire contents of a file as a string.
   *
   * @param path the file path
   * @return the file content
   * @throws Exception if reading fails
   */
  @Override
  public String readString(Path path) throws Exception {
    return Files.readString(path);
  }

  /**
   * Deletes a file.
   *
   * @param path the file path
   * @throws Exception if deletion fails
   */
  @Override
  public void delete(Path path) throws Exception {
    Files.delete(path);
  }

  /**
   * Deletes a file if it exists.
   *
   * @param path the file path
   * @return true if deleted, false if it didn't exist
   * @throws Exception if deletion fails
   */
  @Override
  public boolean deleteIfExists(Path path) throws Exception {
    return Files.deleteIfExists(path);
  }

  /**
   * Checks if a file exists.
   *
   * @param path the file path
   * @return true if the file exists
   */
  @Override
  public boolean exists(Path path) {
    return Files.exists(path);
  }
}
