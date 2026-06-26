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
package com.infenia.yukta.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

class YuktaCliTest {

  @Test
  void constructor_createsInstance() {
    YuktaCli cli = new YuktaCli();

    assertThat(cli).isNotNull();
  }

  @Test
  void run_displaysUsage() {
    ByteArrayOutputStream outContent = new ByteArrayOutputStream();
    System.setOut(new PrintStream(outContent));

    YuktaCli cli = new YuktaCli();
    cli.run();

    String output = outContent.toString();
    assertThat(output).contains("Yukta CLI");
    System.setOut(System.out);
  }

  @Test
  void commandAnnotation_hasCorrectAttributes() {
    YuktaCli cli = new YuktaCli();
    CommandLine cmd = new CommandLine(cli);

    assertThat(cmd.getCommandName()).isEqualTo("yukta");
  }

  @Test
  void run_isRunnable() {
    YuktaCli cli = new YuktaCli();

    assertThat(cli).isInstanceOf(Runnable.class);
  }
}
