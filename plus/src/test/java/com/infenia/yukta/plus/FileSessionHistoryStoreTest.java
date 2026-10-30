// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plus;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.testsupport.ExpectedErrorLog;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@NoArgsConstructor
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
@ExpectedErrorLog("ERROR com.infenia.yukta.plus.FileSessionHistoryStore - ")
class FileSessionHistoryStoreTest {

  @TempDir /* default */ Path dir;

  @Test
  void savedHistory_isReadBackByNewStore() {
    final List<Map<String, String>> turns =
        List.of(
            Map.of("role", "user", "content", "q1"), Map.of("role", "assistant", "content", "a1"));
    new FileSessionHistoryStore(dir.toString()).save("s", turns);

    assertThat(new FileSessionHistoryStore(dir.toString()).load("s")).contains(turns);
  }

  @Test
  void deletedHistory_isGone() throws IOException {
    final FileSessionHistoryStore store = new FileSessionHistoryStore(dir.toString());
    store.save("s", List.of(Map.of("role", "user", "content", "x")));

    store.delete("s");
    store.delete("never-stored");
    Files.createDirectories(dir.resolve("d.json"));
    Files.writeString(dir.resolve("d.json/inner"), "x");
    store.delete("d"); // not a file: logged, not thrown

    assertThat(store.load("s")).isEmpty();
  }

  @Test
  void sessionIdCannotEscape_andUnknownSessionsAreEmpty() throws IOException {
    final FileSessionHistoryStore store = new FileSessionHistoryStore(dir.toString());
    store.save("../../evil", List.of(Map.of("role", "user", "content", "x")));

    assertThat(dir.getParent().resolve("evil.json")).doesNotExist();
    try (var files = Files.list(dir)) {
      assertThat(files.map(f -> dir.relativize(f).toString()).toList())
          .containsExactly("..%2F..%2Fevil.json");
    }
    assertThat(store.load("other")).isEmpty();
  }

  @Test
  void unreadableFileMeansEmpty_andUnwritableDoesNotThrow() throws IOException {
    Files.writeString(dir.resolve("broken.json"), "not json");
    assertThat(new FileSessionHistoryStore(dir.toString()).load("broken")).isEmpty();

    // the "directory" is a regular file, so nothing can be written beneath it
    final Path blocker = Files.createFile(dir.resolve("blocker"));
    final FileSessionHistoryStore unwritable = new FileSessionHistoryStore(blocker.toString());
    unwritable.save("s", List.of(Map.of("role", "user", "content", "kept")));
    assertThat(unwritable.load("s")).isEmpty();
  }
}
