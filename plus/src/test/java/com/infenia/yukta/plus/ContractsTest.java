// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plus;

import com.infenia.yukta.plugin.store.NodeCheckpointStore;
import com.infenia.yukta.plus.testkit.ActivityStoreContract;
import com.infenia.yukta.plus.testkit.ExecutionLogStoreContract;
import com.infenia.yukta.plus.testkit.InputRequestStoreContract;
import com.infenia.yukta.plus.testkit.LeaderElectionContract;
import com.infenia.yukta.plus.testkit.NodeCheckpointStoreContract;
import com.infenia.yukta.plus.testkit.NotificationStoreContract;
import com.infenia.yukta.plus.testkit.SessionConfigStoreContract;
import com.infenia.yukta.plus.testkit.WorkflowDefinitionStoreContract;
import com.infenia.yukta.service.activity.ActivityStore;
import com.infenia.yukta.service.activity.InMemoryActivityStore;
import com.infenia.yukta.service.input.InMemoryInputRequestStore;
import com.infenia.yukta.service.input.InputRequestStore;
import com.infenia.yukta.service.leader.LeaderElection;
import com.infenia.yukta.service.leader.SingleServerLeader;
import com.infenia.yukta.service.log.ExecutionLogStore;
import com.infenia.yukta.service.log.InMemoryExecutionLogStore;
import com.infenia.yukta.service.notify.InMemoryNotificationStore;
import com.infenia.yukta.service.notify.NotificationStore;
import com.infenia.yukta.service.sessionstore.InMemorySessionConfigStore;
import com.infenia.yukta.service.sessionstore.SessionConfigStore;
import com.infenia.yukta.service.store.InMemoryNodeCheckpointStore;
import com.infenia.yukta.service.workflow.store.InMemoryWorkflowDefinitionStore;
import com.infenia.yukta.service.workflow.store.WorkflowDefinitionStore;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Nested;

/** Runs the contract tests of the test kit against the stores that ship with the repository. */
@NoArgsConstructor
@SuppressWarnings({"PMD.TestClassWithoutTestCases", "PMD.PublicMemberInNonPublicType"})
class ContractsTest {

  @Nested
  @NoArgsConstructor
  public class InMemoryExecutionLog extends ExecutionLogStoreContract {
    @Override
    protected ExecutionLogStore create() {
      return new InMemoryExecutionLogStore();
    }
  }

  @Nested
  @NoArgsConstructor
  public class InMemoryActivity extends ActivityStoreContract {
    @Override
    protected ActivityStore create() {
      return new InMemoryActivityStore(100);
    }
  }

  @Nested
  @NoArgsConstructor
  public class InMemoryInputRequests extends InputRequestStoreContract {
    @Override
    protected InputRequestStore create() {
      return new InMemoryInputRequestStore(100);
    }
  }

  @Nested
  @NoArgsConstructor
  public class InMemoryNotifications extends NotificationStoreContract {
    @Override
    protected NotificationStore create() {
      return new InMemoryNotificationStore(1000);
    }
  }

  @Nested
  @NoArgsConstructor
  public class InMemoryCheckpoints extends NodeCheckpointStoreContract {
    @Override
    protected NodeCheckpointStore create() {
      return new InMemoryNodeCheckpointStore();
    }
  }

  @Nested
  @NoArgsConstructor
  public class InMemoryDefinitions extends WorkflowDefinitionStoreContract {
    @Override
    protected WorkflowDefinitionStore create() {
      return new InMemoryWorkflowDefinitionStore();
    }
  }

  @Nested
  @NoArgsConstructor
  public class InMemorySessions extends SessionConfigStoreContract {
    @Override
    protected SessionConfigStore create() {
      return new InMemorySessionConfigStore(new InMemoryWorkflowDefinitionStore());
    }
  }

  @Nested
  @NoArgsConstructor
  public class SingleServer extends LeaderElectionContract {
    @Override
    protected LeaderElection createLeader() {
      return new SingleServerLeader();
    }
  }
}
