// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.console;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.StringReader;
import org.jline.utils.NonBlocking;
import org.jline.utils.NonBlockingReader;
import org.junit.jupiter.api.Test;

class ConsoleMouseTest {

  private static NonBlockingReader reader(final String s) {
    return NonBlocking.nonBlocking("test", new StringReader(s));
  }

  private static String x10(final int button, final int col, final int row) {
    return new String(new char[] {(char) button, (char) col, (char) row});
  }

  private static String sgr(final String s) throws IOException {
    return ConsoleMouse.toKey(ConsoleMouse.readSgr(reader(s)));
  }

  @Test
  void leftClickPressesAndReleaseDoesNothing() throws IOException {
    assertThat(sgr("0;5;3M")).isEqualTo("CLICK:4:2");
    assertThat(sgr("0;5;3m")).isNull();
  }

  @Test
  void theWheelScrolls() throws IOException {
    assertThat(sgr("64;5;5M")).isEqualTo("UP");
    assertThat(sgr("65;5;5M")).isEqualTo("DOWN");
  }

  @Test
  void movesAndOtherButtonsAreIgnored() throws IOException {
    assertThat(sgr("35;5;5M")).isNull(); // motion
    assertThat(sgr("2;5;5M")).isNull(); // right button
  }

  @Test
  void cutShortOrBrokenReportEndsInsteadOfWaiting() throws IOException {
    assertThat(ConsoleMouse.readSgr(reader("0;5"))).isNull(); // ends before M or m
    assertThat(ConsoleMouse.readSgr(reader("x;y;zM"))).isNull();
    assertThat(ConsoleMouse.readSgr(reader("0;5M"))).isNull();
    assertThat(ConsoleMouse.readSgr(reader("1".repeat(40)))).isNull();
    assertThat(ConsoleMouse.toKey(null)).isNull();
  }

  @Test
  void theOldX10FormToo() throws IOException {
    // button 0 at column 5, row 3: each byte is the value plus 32
    assertThat(ConsoleMouse.toKey(ConsoleMouse.readX10(reader(x10(32, 37, 35)))))
        .isEqualTo("CLICK:4:2");
    assertThat(ConsoleMouse.toKey(ConsoleMouse.readX10(reader(x10(96, 37, 35))))).isEqualTo("UP");
    assertThat(ConsoleMouse.toKey(ConsoleMouse.readX10(reader(x10(35, 37, 35)))))
        .isNull(); // release
    // a wheel report whose low bits look like a release is still a wheel report
    assertThat(ConsoleMouse.toKey(ConsoleMouse.readX10(reader(x10(99, 37, 35))))).isEqualTo("DOWN");
    assertThat(ConsoleMouse.readX10(reader("  "))).isNull();
  }
}
