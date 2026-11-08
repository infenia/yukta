// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.control;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import reactor.util.context.Context;

class ControlActorTest {

  @Test
  void kindOfReadsTheKindTheCommandWasSentWith() {
    assertThat(ControlActor.kindOf(Context.of(ControlActor.KIND_KEY, ControlActor.Kind.KEY)))
        .isEqualTo(ControlActor.Kind.KEY);
    assertThat(ControlActor.kindOf(Context.of(ControlActor.KIND_KEY, ControlActor.Kind.TOKEN)))
        .isEqualTo(ControlActor.Kind.TOKEN);
  }

  @Test
  void kindOfTrustsTheKindNotTheShapeOfTheActorString() {
    // A supervisor id can look like an API key name; the kind decides, not the actor string.
    final Context context =
        Context.of(
            ControlActor.CONTEXT_KEY,
            "key:ops",
            ControlActor.KIND_KEY,
            ControlActor.Kind.SUPERVISOR);

    assertThat(ControlActor.kindOf(context)).isEqualTo(ControlActor.Kind.SUPERVISOR);
  }

  @Test
  void kindOfIsNullWhenNoKindWasSet() {
    assertThat(ControlActor.kindOf(Context.empty())).isNull();
  }
}
