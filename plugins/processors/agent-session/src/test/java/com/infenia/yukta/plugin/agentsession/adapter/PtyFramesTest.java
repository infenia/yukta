// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.agentsession.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** A line of the terminal host is a frame, or nothing. */
@NoArgsConstructor
class PtyFramesTest {

  private final PtyFrames frames = new PtyFrames(new ObjectMapper());

  @Test
  void whatIsNotFrameIsNull() {
    assertThat(frames.parse("not json")).isNull();
    assertThat(frames.parse("[1]")).isNull();
    assertThat(frames.parse("{\"d\":\"AQ==\"}")).isNull();
    assertThat(frames.parse("{\"t\":3}")).isNull();
    assertThat(frames.parse("")).isNull();
    assertThat(frames.parse("{\"t\":\"later\"}")).isNull();
  }

  @Test
  void outputWithNoBytesOrBadBase64IsNull() {
    assertThat(frames.parse("{\"t\":\"out\",\"d\":\"\"}")).isNull();
    assertThat(frames.parse("{\"t\":\"out\",\"d\":\"***\"}")).isNull();
    assertThat(frames.parse("{\"t\":\"out\"}")).isNull();
    assertThat(frames.parse("{\"t\":\"out\",\"d\":\"AQ==\"}").data()).containsExactly(1);
  }

  @Test
  void screenAndExitFramesCarryTheirFields() {
    final PtyFrames.Host screen =
        frames.parse("{\"t\":\"screen\",\"text\":\"hi\",\"cols\":80,\"rows\":24}");
    assertThat(screen.text()).isEqualTo("hi");
    assertThat(screen.cols()).isEqualTo(80);
    assertThat(frames.parse("{\"t\":\"exit\",\"code\":7}").code()).isEqualTo(7);
    assertThat(frames.parse("{\"t\":\"exit\"}").code()).isEqualTo(-1);
  }
}
