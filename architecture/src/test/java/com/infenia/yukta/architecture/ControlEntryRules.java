// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaCall;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import java.util.Set;

/**
 * Control commands and starts have one entry, {@code SupervisedControl}. Only the control and
 * supervisor packages may reach the gateway's command methods or the dispatcher, and the supervisor
 * package goes through {@code SupervisedControl} too.
 */
public final class ControlEntryRules {

  private static final Set<String> GATEWAYS =
      Set.of(
          "com.infenia.yukta.service.control.gateway.ControlBusGateway",
          "com.infenia.yukta.service.control.gateway.DefaultControlBusGateway");

  private static final String DISPATCHER =
      "com.infenia.yukta.service.control.directive.DirectiveDispatcher";

  /** The gateway methods that change a run, start one or change the server. */
  private static final Set<String> COMMANDS =
      Set.of(
          "executeCommand",
          "pauseWorkflow",
          "resumeWorkflow",
          "pauseNode",
          "resumeNode",
          "stopNode",
          "skipNode",
          "addBreakpoint",
          "clearBreakpoint",
          "enableStepMode",
          "disableStepMode",
          "stepNode",
          "stopWorkflow",
          "stopExecution",
          "startWorkflow",
          "restartWorkflow",
          "restartFromNode",
          "retryNode",
          "changeLimits",
          "changePriority",
          "answerInput",
          "escalateInput",
          "recordAdminAction");

  private static final Set<String> DISPATCH = Set.of("dispatchOrdered", "dispatch");

  /** A call of a gateway command method. */
  static final DescribedPredicate<JavaCall<?>> GATEWAY_COMMAND =
      DescribedPredicate.describe(
          "a gateway command method",
          call ->
              GATEWAYS.contains(call.getTargetOwner().getName())
                  && COMMANDS.contains(call.getName()));

  /** A call that queues a command on the dispatcher. */
  static final DescribedPredicate<JavaCall<?>> DISPATCHER_CALL =
      DescribedPredicate.describe(
          "a dispatcher dispatch method",
          call ->
              DISPATCHER.equals(call.getTargetOwner().getName())
                  && DISPATCH.contains(call.getName()));

  /** Outside the control and supervisor packages nothing applies a command by itself. */
  @ArchTest
  public static final ArchRule ONLY_CONTROL_APPLIES_COMMANDS =
      noClasses()
          .that()
          .resideOutsideOfPackages("..service.control..", "..service.supervisor..")
          .should()
          .callMethodWhere(GATEWAY_COMMAND.or(DISPATCHER_CALL))
          .because("every control command and start goes through SupervisedControl");

  /** The supervisor host sends its commands through the one entry too. */
  @ArchTest
  public static final ArchRule SUPERVISORS_USE_THE_ENTRY =
      noClasses()
          .that()
          .resideInAPackage("..service.supervisor..")
          .should()
          .callMethodWhere(GATEWAY_COMMAND.or(DISPATCHER_CALL))
          .because("an engine's directive is a command like any other: SupervisedControl.engine");

  private ControlEntryRules() {}
}
