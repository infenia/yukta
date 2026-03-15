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
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.infenia.yukta.plugin.core.PluginCategory;
import com.infenia.yukta.plugin.core.UiDesign;
import java.util.List;
import org.junit.jupiter.api.Test;

class PluginDetailsTest {

  @Test
  void testPluginDetails() {
    PluginDetails details = new PluginDetails("t", PluginCategory.PROCESSOR, "d", "p", null, null);
    assertEquals("t", details.type());
    assertNotNull(details.outputPorts());
    assertTrue(details.outputPorts().isEmpty());

    List<String> ports = List.of("p1", "p2");
    UiDesign ui = new UiDesign("<div>icon</div>", 100, 100);
    PluginDetails details2 = new PluginDetails("t", PluginCategory.PROCESSOR, "d", "p", ui, ports);
    assertEquals("t", details2.type());
    assertEquals(ports, details2.outputPorts());
    assertEquals(ui, details2.uiDesign());

    // Test record methods
    PluginDetails details3 = new PluginDetails("t", PluginCategory.PROCESSOR, "d", "p", ui, ports);
    assertEquals(details2, details3);
    assertEquals(details2.hashCode(), details3.hashCode());
    assertNotNull(details2.toString());
  }
}
