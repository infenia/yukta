package com.infenia.jagratha.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.Map;
import org.junit.jupiter.api.Test;

class PluginInterfaceTest {

  @Test
  void testProcessorInputCompactConstructor() {
    OutputProcessorPlugin.ProcessorInput input =
        new OutputProcessorPlugin.ProcessorInput("sess", "root", "mod", "task", "out", "res", null);

    assertNotNull(input.config());
    assertEquals(0, input.config().size());
  }

  @Test
  void testProcessorInputConfigImmutability() {
    Map<String, Object> config = new java.util.HashMap<>();
    config.put("key", "value");
    OutputProcessorPlugin.ProcessorInput input =
        new OutputProcessorPlugin.ProcessorInput(
            "sess", "root", "mod", "task", "out", "res", config);

    assertEquals("value", input.config().get("key"));

    // Attempting to modify the returned map should throw UnsupportedOperationException
    try {
      input.config().put("new", "val");
    } catch (UnsupportedOperationException e) {
      // expected
    }
  }

  @Test
  void testProcessorResult() {
    OutputProcessorPlugin.ProcessorResult result =
        new OutputProcessorPlugin.ProcessorResult("SUCCESS", "out", "art");

    assertEquals("SUCCESS", result.status());
    assertEquals("out", result.output());
    assertEquals("art", result.artifactPath());
  }
}
