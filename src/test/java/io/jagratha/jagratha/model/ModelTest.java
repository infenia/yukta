package io.jagratha.jagratha.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

class ModelTest {

  @Test
  void testFileRequest() {
    FileRequest request = new FileRequest("path", "session-1", "content");
    assertEquals("path", request.path());
    assertEquals("session-1", request.sessionId());
    assertEquals("content", request.content());
    assertNotNull(request.toString());
  }

  @Test
  void testTaskResponse() {
    TaskResponse response = new TaskResponse("SUCCESS", "output");
    assertEquals("SUCCESS", response.status());
    assertEquals("output", response.output());
    assertNotNull(response.toString());
  }

  @Test
  void testTaskRequest() {
    TaskRequest request = new TaskRequest("session-1");
    assertEquals("session-1", request.sessionId());
  }
}
