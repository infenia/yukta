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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DefaultFileSystemAdapterTest {

  private DefaultFileSystemAdapter adapter;

  @TempDir private Path tempDir;

  @BeforeEach
  void setUp() {
    adapter = new DefaultFileSystemAdapter();
  }

  // createDirectories() tests

  @Test
  void createDirectories_validPath_createsDirectory() throws Exception {
    Path dirPath = tempDir.resolve("testdir");
    assertThat(dirPath).doesNotExist();

    adapter.createDirectories(dirPath);

    assertThat(dirPath).exists().isDirectory();
  }

  @Test
  void createDirectories_nestedPath_createsAllParents() throws Exception {
    Path nestedPath = tempDir.resolve("parent/child/grandchild");
    assertThat(nestedPath).doesNotExist();

    adapter.createDirectories(nestedPath);

    assertThat(nestedPath).exists().isDirectory();
  }

  @Test
  void createDirectories_pathAlreadyExists_succeedsIdempotently() throws Exception {
    Path dirPath = tempDir.resolve("existingdir");
    Files.createDirectory(dirPath);

    adapter.createDirectories(dirPath);

    assertThat(dirPath).exists().isDirectory();
  }

  // writeString() tests

  @Test
  void writeString_validPathAndContent_writesStringToFile() throws Exception {
    Path filePath = tempDir.resolve("testfile.txt");
    String content = "Hello, World!";

    adapter.writeString(filePath, content, StandardOpenOption.CREATE, StandardOpenOption.WRITE);

    assertThat(filePath).exists();
    assertThat(Files.readString(filePath)).isEqualTo(content);
  }

  @Test
  void writeString_fileAlreadyExists_truncatesAndWrites() throws Exception {
    Path filePath = tempDir.resolve("testfile.txt");
    String originalContent = "Original content";
    String newContent = "New content";

    Files.writeString(
        filePath, originalContent, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
    adapter.writeString(
        filePath, newContent, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);

    assertThat(Files.readString(filePath)).isEqualTo(newContent);
  }

  @Test
  void writeString_multipleOptions_appliesAllOptions() throws Exception {
    Path filePath = tempDir.resolve("testfile.txt");
    String content = "Test content";

    adapter.writeString(
        filePath,
        content,
        StandardOpenOption.CREATE,
        StandardOpenOption.WRITE,
        StandardOpenOption.TRUNCATE_EXISTING);

    assertThat(filePath).exists();
    assertThat(Files.readString(filePath)).isEqualTo(content);
  }

  @Test
  void writeString_emptyContent_createsEmptyFile() throws Exception {
    Path filePath = tempDir.resolve("emptyfile.txt");
    String emptyContent = "";

    adapter.writeString(
        filePath, emptyContent, StandardOpenOption.CREATE, StandardOpenOption.WRITE);

    assertThat(filePath).exists();
    assertThat(Files.readString(filePath)).isEmpty();
  }

  // readString() tests

  @Test
  void readString_fileExists_returnsFileContent() throws Exception {
    Path filePath = tempDir.resolve("testfile.txt");
    String expectedContent = "File content here";
    Files.writeString(filePath, expectedContent);

    String result = adapter.readString(filePath);

    assertThat(result).isEqualTo(expectedContent);
  }

  @Test
  void readString_emptyFile_returnsEmptyString() throws Exception {
    Path filePath = tempDir.resolve("emptyfile.txt");
    Files.createFile(filePath);

    String result = adapter.readString(filePath);

    assertThat(result).isEmpty();
  }

  @Test
  void readString_fileNotFound_throwsException() {
    Path nonExistentFile = tempDir.resolve("nonexistent.txt");

    assertThatThrownBy(() -> adapter.readString(nonExistentFile)).isInstanceOf(IOException.class);
  }

  @Test
  void readString_multilineContent_returnsCompleteContent() throws Exception {
    Path filePath = tempDir.resolve("multiline.txt");
    String content = "Line 1\nLine 2\nLine 3";
    Files.writeString(filePath, content);

    String result = adapter.readString(filePath);

    assertThat(result).isEqualTo(content);
  }

  // delete() tests

  @Test
  void delete_fileExists_deletesFile() throws Exception {
    Path filePath = tempDir.resolve("todelete.txt");
    Files.createFile(filePath);
    assertThat(filePath).exists();

    adapter.delete(filePath);

    assertThat(filePath).doesNotExist();
  }

  @Test
  void delete_fileNotFound_throwsException() {
    Path nonExistentFile = tempDir.resolve("nonexistent.txt");

    assertThatThrownBy(() -> adapter.delete(nonExistentFile)).isInstanceOf(IOException.class);
  }

  @Test
  void delete_directoryNotEmpty_throwsException() throws Exception {
    Path dirPath = tempDir.resolve("nonemptydir");
    Files.createDirectory(dirPath);
    Files.createFile(dirPath.resolve("file.txt"));

    assertThatThrownBy(() -> adapter.delete(dirPath)).isInstanceOf(IOException.class);
  }

  // deleteIfExists() tests

  @Test
  void deleteIfExists_fileExists_deletesAndReturnsTrue() throws Exception {
    Path filePath = tempDir.resolve("todelete.txt");
    Files.createFile(filePath);

    boolean result = adapter.deleteIfExists(filePath);

    assertThat(result).isTrue();
    assertThat(filePath).doesNotExist();
  }

  @Test
  void deleteIfExists_fileNotFound_returnsFalse() throws Exception {
    Path nonExistentFile = tempDir.resolve("nonexistent.txt");

    boolean result = adapter.deleteIfExists(nonExistentFile);

    assertThat(result).isFalse();
  }

  @Test
  void deleteIfExists_directoryExists_deletesAndReturnsTrue() throws Exception {
    Path dirPath = tempDir.resolve("emptydir");
    Files.createDirectory(dirPath);

    boolean result = adapter.deleteIfExists(dirPath);

    assertThat(result).isTrue();
    assertThat(dirPath).doesNotExist();
  }

  // exists() tests

  @Test
  void exists_fileExists_returnsTrue() throws Exception {
    Path filePath = tempDir.resolve("existingfile.txt");
    Files.createFile(filePath);

    boolean result = adapter.exists(filePath);

    assertThat(result).isTrue();
  }

  @Test
  void exists_fileNotFound_returnsFalse() {
    Path nonExistentFile = tempDir.resolve("nonexistent.txt");

    boolean result = adapter.exists(nonExistentFile);

    assertThat(result).isFalse();
  }

  @Test
  void exists_directoryExists_returnsTrue() throws Exception {
    Path dirPath = tempDir.resolve("existingdir");
    Files.createDirectory(dirPath);

    boolean result = adapter.exists(dirPath);

    assertThat(result).isTrue();
  }
}
