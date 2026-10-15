// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.leader;

import com.infenia.yukta.service.log.ExecutionRecovery;
import com.infenia.yukta.service.orchestrator.ExecutionResumer;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Recovery of interrupted runs. With several servers it happens when a server becomes the leader (a
 * standby that starts must not mark the runs of a living leader aborted). With {@code
 * yukta.restart.auto-resume=true}, whenever a recovery finds interrupted runs, whether at startup
 * of a single server or on takeover, they are resumed from their checkpoints. Resuming needs a
 * durable checkpoint store and execution log (files on a shared volume, or PostgreSQL).
 */
@Slf4j
@Component
public class TakeoverRecovery implements SmartInitializingSingleton {

  /** Decides who is the leader. */
  private final LeaderElection election;

  /** Recovers the log. */
  private final ExecutionRecovery recovery;

  /** Resumes aborted runs. */
  private final ExecutionResumer resumer;

  /**
   * Creates the component.
   *
   * @param election decides who is the leader
   * @param recovery recovers the log
   * @param resumer resumes aborted runs
   * @param autoResume whether interrupted runs are resumed on their own
   */
  @SuppressFBWarnings("EI_EXPOSE_REP2") // all three are shared Spring beans
  public TakeoverRecovery(
      final LeaderElection election,
      final ExecutionRecovery recovery,
      final ExecutionResumer resumer,
      @Value("${yukta.restart.auto-resume:false}") final boolean autoResume) {
    this.election = election;
    this.recovery = recovery;
    this.resumer = resumer;
    if (autoResume) {
      recovery.onRecovered(this::resumeAll);
    }
  }

  @Override
  public void afterSingletonsInstantiated() {
    election.onTakeover(recovery::recover);
  }

  /** Resumes the interrupted runs one after the other; one that cannot be resumed is skipped. */
  /* default */ void resumeAll(final List<String> aborted) {
    Flux.fromIterable(aborted)
        .concatMap(
            id ->
                resumer
                    .resume(id)
                    .doOnNext(
                        resumed ->
                            log.atInfo()
                                .addKeyValue("executionId", id)
                                .addKeyValue("resumedAs", resumed)
                                .log("Resumed an interrupted run"))
                    .onErrorResume(
                        error -> {
                          log.atWarn()
                              .addKeyValue("executionId", id)
                              .setCause(error)
                              .log("Could not resume an interrupted run");
                          return Mono.empty();
                        }))
        .subscribeOn(Schedulers.boundedElastic())
        .subscribe();
  }
}
