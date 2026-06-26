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

import org.junit.jupiter.api.Test;

class DaemonStatusTest {

  @Test
  void constructor_createsRecord_withRunningTrue() {
    DaemonStatus status = new DaemonStatus(true, 1234L, "http://localhost:8080");

    assertThat(status.running()).isTrue();
    assertThat(status.pid()).isEqualTo(1234L);
    assertThat(status.url()).isEqualTo("http://localhost:8080");
  }

  @Test
  void constructor_createsRecord_withRunningFalse() {
    DaemonStatus status = new DaemonStatus(false, 0L, null);

    assertThat(status.running()).isFalse();
    assertThat(status.pid()).isZero();
    assertThat(status.url()).isNull();
  }

  @Test
  void constructor_withNegativePid_storesValue() {
    DaemonStatus status = new DaemonStatus(false, -1L, null);

    assertThat(status.pid()).isEqualTo(-1L);
  }

  @Test
  void constructor_withLargePid_storesValue() {
    DaemonStatus status = new DaemonStatus(true, 999999L, "http://localhost:8080");

    assertThat(status.pid()).isEqualTo(999999L);
  }

  @Test
  void constructor_withDifferentUrls_storesValue() {
    DaemonStatus status1 = new DaemonStatus(true, 1234L, "http://127.0.0.1:8080");
    DaemonStatus status2 = new DaemonStatus(true, 1234L, "http://192.168.1.1:9090");

    assertThat(status1.url()).isEqualTo("http://127.0.0.1:8080");
    assertThat(status2.url()).isEqualTo("http://192.168.1.1:9090");
  }

  @Test
  void equals_withSameValues_returnsTrue() {
    DaemonStatus status1 = new DaemonStatus(true, 5678L, "http://localhost:9090");
    DaemonStatus status2 = new DaemonStatus(true, 5678L, "http://localhost:9090");

    assertThat(status1).isEqualTo(status2);
  }

  @Test
  void equals_withDifferentRunning_returnsFalse() {
    DaemonStatus status1 = new DaemonStatus(true, 1234L, "http://localhost:8080");
    DaemonStatus status2 = new DaemonStatus(false, 1234L, "http://localhost:8080");

    assertThat(status1).isNotEqualTo(status2);
  }

  @Test
  void equals_withDifferentPid_returnsFalse() {
    DaemonStatus status1 = new DaemonStatus(true, 1234L, "http://localhost:8080");
    DaemonStatus status2 = new DaemonStatus(true, 5678L, "http://localhost:8080");

    assertThat(status1).isNotEqualTo(status2);
  }

  @Test
  void equals_withDifferentUrl_returnsFalse() {
    DaemonStatus status1 = new DaemonStatus(true, 1234L, "http://localhost:8080");
    DaemonStatus status2 = new DaemonStatus(true, 1234L, "http://localhost:9090");

    assertThat(status1).isNotEqualTo(status2);
  }

  @Test
  void equals_withNullUrl_handlesNull() {
    DaemonStatus status1 = new DaemonStatus(false, -1L, null);
    DaemonStatus status2 = new DaemonStatus(false, -1L, null);

    assertThat(status1).isEqualTo(status2);
  }

  @Test
  void hashCode_withSameValues_returnsEqualHashCode() {
    DaemonStatus status1 = new DaemonStatus(true, 1234L, "http://localhost:8080");
    DaemonStatus status2 = new DaemonStatus(true, 1234L, "http://localhost:8080");

    assertThat(status1.hashCode()).isEqualTo(status2.hashCode());
  }

  @Test
  void hashCode_withDifferentValues_returnsDifferentHashCode() {
    DaemonStatus status1 = new DaemonStatus(true, 1234L, "http://localhost:8080");
    DaemonStatus status2 = new DaemonStatus(false, 5678L, "http://localhost:9090");

    assertThat(status1.hashCode()).isNotEqualTo(status2.hashCode());
  }

  @Test
  void toString_containsAllFields() {
    DaemonStatus status = new DaemonStatus(true, 1234L, "http://localhost:8080");

    String result = status.toString();

    assertThat(result)
        .contains("DaemonStatus")
        .contains("true")
        .contains("1234")
        .contains("http://localhost:8080");
  }

  @Test
  void toString_withNullUrl_includesNull() {
    DaemonStatus status = new DaemonStatus(false, -1L, null);

    String result = status.toString();

    assertThat(result).contains("DaemonStatus");
  }

  @Test
  void record_fieldsAreAccessible() {
    DaemonStatus status = new DaemonStatus(true, 9999L, "http://test:3000");

    assertThat(status.running()).isTrue();
    assertThat(status.pid()).isEqualTo(9999L);
    assertThat(status.url()).isEqualTo("http://test:3000");
  }
}
