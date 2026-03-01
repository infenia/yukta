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
package com.infenia.yukta.plugin.fileupdate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.infenia.yukta.plugin.message.DefaultMessage;
import com.infenia.yukta.plugin.message.Message;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

class FileUpdateRecordPluginTest {

  private FileUpdateRecordPlugin plugin;
  private final ObjectMapper objectMapper = new ObjectMapper();

  @TempDir Path tempDir;

  @BeforeEach
  void setUp() {
    plugin = new FileUpdateRecordPlugin();
  }

  @Test
  void testConsumeAndRecordStatus() throws IOException {
    Map<String, Object> config = Map.of("outputDir", tempDir.toString());
    Map<String, String> payload = Map.of("path", "src/App.java", "status", "SUCCESS");
    Message msg = DefaultMessage.create(UUID.randomUUID(), payload);

    StepVerifier.create(plugin.consume(Flux.just(msg), config)).verifyComplete();

    Path recordFile = tempDir.resolve("file-status.json");
    assertTrue(Files.exists(recordFile));

    Map<String, String> statusMap = objectMapper.readValue(Files.readString(recordFile), Map.class);
    assertEquals("SUCCESS", statusMap.get("src/App.java"));
  }

  @Test
  void testUpdateExistingStatus() throws IOException {
    Map<String, Object> config = Map.of("outputDir", tempDir.toString());

    // First record
    Message msg1 =
        DefaultMessage.create(UUID.randomUUID(), Map.of("path", "file.txt", "status", "PENDING"));
    plugin.consume(Flux.just(msg1), config).block();

    // Second record (update)
    Message msg2 =
        DefaultMessage.create(UUID.randomUUID(), Map.of("path", "file.txt", "status", "SUCCESS"));
    plugin.consume(Flux.just(msg2), config).block();

    Path recordFile = tempDir.resolve("file-status.json");
    Map<String, String> statusMap = objectMapper.readValue(Files.readString(recordFile), Map.class);
    assertEquals("SUCCESS", statusMap.get("file.txt"));
    assertEquals(1, statusMap.size());
  }
}
