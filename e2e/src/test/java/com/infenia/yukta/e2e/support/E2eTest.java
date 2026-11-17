// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.e2e.support;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Marks a test class that runs against a real Yukta server. {@link YuktaServer} starts or finds the
 * server and injects {@link Api} and {@link Scenarios} into test methods.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Tag("e2e")
@ExtendWith(YuktaServer.class)
public @interface E2eTest {}
