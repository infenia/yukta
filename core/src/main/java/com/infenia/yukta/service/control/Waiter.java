// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.control;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import reactor.core.publisher.Sinks;

/** A start waiting for a slot. */
record Waiter(
    String key,
    @Nullable String executionId,
    AtomicInteger priority,
    Sinks.One<ExecutionScheduler.Permit> grant,
    AtomicReference<ExecutionScheduler.Permit> granted) {}
