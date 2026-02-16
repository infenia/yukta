package io.jagratha.jagratha;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import org.junit.jupiter.api.Test;

class JagrathaApplicationTest {

  @Test
  void main() {
    assertDoesNotThrow(() -> JagrathaApplication.main(new String[] {"--server.port=0"}));
  }
}
