package com.infenia.jagratha.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.infenia.jagratha.model.AppConfigData;
import com.infenia.jagratha.model.ConfigRequest;
import com.infenia.jagratha.model.WorkflowConfig;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class AppConfigMapperTest {

  @Autowired private AppConfigMapper mapper;

  @Test
  void testMapping() {
    ConfigRequest request =
        new ConfigRequest(
            "sess-1",
            "/path",
            "plugin",
            Map.of("k", "v"),
            List.of("t1"),
            List.of(new WorkflowConfig("w1", null, null)),
            100L,
            "/logs",
            "/results");

    AppConfigData data = mapper.toData(request);

    assertNotNull(data);
    assertEquals("sess-1", data.sessionId());
    assertEquals("/path", data.projectPath());
    assertEquals("plugin", data.pluginName());
    assertEquals(Map.of("k", "v"), data.pluginConfig());
    assertEquals(List.of("t1"), data.tasks());
    assertEquals(1, data.workflows().size());
    assertEquals(100L, data.executionTimeout());
    assertEquals("/logs", data.fileLogDir());
    assertEquals("/results", data.resultLogDir());
  }
}
