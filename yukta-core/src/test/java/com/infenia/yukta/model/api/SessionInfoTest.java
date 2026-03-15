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
package com.infenia.yukta.model.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class SessionInfoTest {

  @Test
  void testSessionInfoWithActiveStatus() {
    LocalDateTime createdAt = LocalDateTime.of(2026, 3, 13, 10, 30, 0);
    LocalDateTime lastModified = LocalDateTime.of(2026, 3, 13, 11, 45, 30);

    SessionInfo info = new SessionInfo("sess-123", 5, createdAt, lastModified, "active");

    assertEquals("sess-123", info.sessionId());
    assertEquals(5, info.workflowCount());
    assertEquals(createdAt, info.createdAt());
    assertEquals(lastModified, info.lastModified());
    assertEquals("active", info.status());
  }

  @Test
  void testSessionInfoWithIdleStatus() {
    LocalDateTime createdAt = LocalDateTime.of(2026, 3, 12, 8, 15, 0);
    LocalDateTime lastModified = LocalDateTime.of(2026, 3, 13, 9, 20, 0);

    SessionInfo info = new SessionInfo("sess-456", 2, createdAt, lastModified, "idle");

    assertEquals("sess-456", info.sessionId());
    assertEquals(2, info.workflowCount());
    assertEquals(createdAt, info.createdAt());
    assertEquals(lastModified, info.lastModified());
    assertEquals("idle", info.status());
  }

  @Test
  void testSessionInfoWithErrorStatus() {
    LocalDateTime createdAt = LocalDateTime.of(2026, 3, 11, 14, 0, 0);
    LocalDateTime lastModified = LocalDateTime.of(2026, 3, 13, 16, 30, 0);

    SessionInfo info = new SessionInfo("sess-789", 0, createdAt, lastModified, "error");

    assertEquals("sess-789", info.sessionId());
    assertEquals(0, info.workflowCount());
    assertEquals(createdAt, info.createdAt());
    assertEquals(lastModified, info.lastModified());
    assertEquals("error", info.status());
  }

  @Test
  void testSessionInfoFieldsNotNull() {
    LocalDateTime now = LocalDateTime.now();
    SessionInfo info = new SessionInfo("sess-xyz", 10, now, now, "active");

    assertNotNull(info.sessionId());
    assertNotNull(info.createdAt());
    assertNotNull(info.lastModified());
    assertNotNull(info.status());
  }

  @Test
  void testSessionInfoWithVariousWorkflowCounts() {
    LocalDateTime now = LocalDateTime.now();

    SessionInfo info0 = new SessionInfo("s1", 0, now, now, "active");
    assertEquals(0, info0.workflowCount());

    SessionInfo info1 = new SessionInfo("s2", 1, now, now, "active");
    assertEquals(1, info1.workflowCount());

    SessionInfo info100 = new SessionInfo("s3", 100, now, now, "active");
    assertEquals(100, info100.workflowCount());
  }
}
