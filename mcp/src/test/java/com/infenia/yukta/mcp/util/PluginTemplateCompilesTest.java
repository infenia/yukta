// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.StringWriter;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

/** The templates of get_plugin_creation_guide must compile against the real plugin API. */
@NoArgsConstructor
class PluginTemplateCompilesTest {

  @Test
  void everyTemplateCompilesAgainstThePluginApi() throws Exception {
    final Path out = Files.createTempDirectory("templates");
    for (final Map.Entry<String, String> template :
        PluginTemplateBuilder.buildTemplates("all").entrySet()) {
      final String source = template.getValue();
      final String name = source.replaceAll("(?s).*public class (\\w+).*", "$1");
      final JavaFileObject file =
          new SimpleJavaFileObject(
              URI.create("string:///" + name + ".java"), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(final boolean ignoreEncodingErrors) {
              return source;
            }
          };
      final StringWriter diagnostics = new StringWriter();

      final boolean compiled =
          ToolProvider.getSystemJavaCompiler()
              .getTask(
                  diagnostics,
                  null,
                  null,
                  List.of(
                      "-classpath", System.getProperty("java.class.path"), "-d", out.toString()),
                  null,
                  List.of(file))
              .call();

      assertThat(compiled).as("%s template: %s", template.getKey(), diagnostics).isTrue();
    }
  }
}
