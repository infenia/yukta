// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.input;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

/** A vote, and the parts of a request that hold the votes of a tier. */
@NoArgsConstructor
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
class VoteTest {

  private static final Instant AT = Instant.parse("2026-11-02T10:00:00Z");

  @Test
  void aVoteKeepsWhoVotedWhatAndHowSure() {
    final Vote vote = new Vote("watch", "approve", 0.9, AT);
    assertThat(vote.voter()).isEqualTo("watch");
    assertThat(vote.decision()).isEqualTo("approve");
    assertThat(vote.confidence()).isEqualTo(0.9);
    assertThat(new Vote("watch", "approve", null, AT).confidence()).isNull();
  }

  @Test
  void aVoteRefusesWhatIsMissingOrOutOfRange() {
    assertThatThrownBy(() -> new Vote(" ", "approve", null, AT))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Vote("watch", " ", null, AT))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Vote("watch", "approve", 1.5, AT))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Vote("watch", "approve", null, null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void aRequestKeepsItsVotesAsACopyAndAcceptsNone() {
    final InputRequestSpec spec = InputFixtures.spec();
    final Vote vote = new Vote("watch", "approve", null, AT);
    final List<Vote> votes = new java.util.ArrayList<>(List.of(vote));
    final InputRequest request =
        new InputRequest(
            "r1",
            "k",
            "s",
            "w",
            "e",
            "n",
            spec,
            InputState.OPEN,
            0,
            "t",
            List.of(),
            null,
            null,
            AT.plusSeconds(5),
            votes,
            AT,
            AT);
    votes.clear();
    assertThat(request.votes()).containsExactly(vote);
    assertThat(request.tierDeadline()).isEqualTo(AT.plusSeconds(5));

    final InputRequest none =
        new InputRequest(
            "r1",
            "k",
            "s",
            "w",
            "e",
            "n",
            spec,
            InputState.OPEN,
            0,
            "t",
            List.of(),
            null,
            null,
            null,
            null,
            AT,
            AT);
    assertThat(none.votes()).isEmpty();
  }

  @Test
  void theSelectorsOfRoutesAreAccepted() {
    assertThat(new Audience(List.of("supervisor:above", "external:webhook:ops", "external:bean")))
        .isNotNull();
    assertThatThrownBy(() -> new Audience(List.of("external:a:b:c")))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
