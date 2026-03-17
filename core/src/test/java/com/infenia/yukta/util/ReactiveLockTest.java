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
package com.infenia.yukta.util;

import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class ReactiveLockTest {

  @Test
  void testAcquireRelease() {
    ReactiveLock lock = new ReactiveLock();

    StepVerifier.create(lock.acquire()).verifyComplete();
    lock.release();

    StepVerifier.create(lock.acquire()).verifyComplete();
    lock.release();
  }

  @Test
  void testWaiting() {
    ReactiveLock lock = new ReactiveLock();
    StepVerifier.create(lock.acquire()).verifyComplete();

    AtomicBoolean secondAcquired = new AtomicBoolean(false);
    Mono<Void> second = lock.acquire().doOnSuccess(v -> secondAcquired.set(true));

    StepVerifier.create(second)
        .expectSubscription()
        .then(
            () -> {
              assert !secondAcquired.get();
              lock.release();
            })
        .verifyComplete();

    assert secondAcquired.get();
  }

  @Test
  void testWithLock() {
    ReactiveLock lock = new ReactiveLock();
    StepVerifier.create(lock.withLock(Mono.just("result"))).expectNext("result").verifyComplete();

    // Verify it was released
    StepVerifier.create(lock.acquire()).verifyComplete();
  }
}
