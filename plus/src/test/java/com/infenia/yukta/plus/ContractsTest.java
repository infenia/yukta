// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plus;

import com.infenia.yukta.config.SessionConfigProperties;
import com.infenia.yukta.plugin.store.NodeCheckpointStore;
import com.infenia.yukta.plus.testkit.ExecutionLogStoreContract;
import com.infenia.yukta.plus.testkit.LeaderElectionContract;
import com.infenia.yukta.plus.testkit.NodeCheckpointStoreContract;
import com.infenia.yukta.plus.testkit.SessionConfigStoreContract;
import com.infenia.yukta.plus.testkit.WorkflowDefinitionStoreContract;
import com.infenia.yukta.service.leader.LeaderElection;
import com.infenia.yukta.service.leader.SingleServerLeader;
import com.infenia.yukta.service.log.ExecutionLogStore;
import com.infenia.yukta.service.log.InMemoryExecutionLogStore;
import com.infenia.yukta.service.log.JsonlExecutionLogStore;
import com.infenia.yukta.service.session.store.FileSessionConfigStore;
import com.infenia.yukta.service.session.store.InMemorySessionConfigStore;
import com.infenia.yukta.service.session.store.SessionConfigStore;
import com.infenia.yukta.service.store.FileNodeCheckpointStore;
import com.infenia.yukta.service.store.InMemoryNodeCheckpointStore;
import com.infenia.yukta.service.workflow.store.FileWorkflowDefinitionStore;
import com.infenia.yukta.service.workflow.store.InMemoryWorkflowDefinitionStore;
import com.infenia.yukta.service.workflow.store.WorkflowDefinitionStore;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** Runs the contract tests of the test kit against the stores that ship with the repository. */
@NoArgsConstructor
@SuppressWarnings({
  "PMD.CommentRequired",
  "PMD.TestClassWithoutTestCases",
  "PMD.PublicMemberInNonPublicType"
})
class ContractsTest {

  private static final ObjectMapper MAPPER = JsonMapper.builder().build();

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
  public class JsonlExecutionLog extends ExecutionLogStoreContract {
    @TempDir /* default */ Path dir;

    private final List<JsonlExecutionLogStore> created = new ArrayList<>();

    @Override
    protected ExecutionLogStore create() {
      final JsonlExecutionLogStore store =
          new JsonlExecutionLogStore(dir.toString(), MAPPER, 500, 10_000, false);
      created.add(store);
      return store;
    }

    // close drains the writer thread; left open it logs once the temp directory is gone, in a later
    // test
    @AfterEach
    void closeStores() {
      created.forEach(JsonlExecutionLogStore::close);
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
  public class FileCheckpoints extends NodeCheckpointStoreContract {
    @TempDir /* default */ Path dir;

    @Override
    protected NodeCheckpointStore create() {
      return new FileNodeCheckpointStore(dir.toString(), MAPPER);
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
  public class FileDefinitions extends WorkflowDefinitionStoreContract {
    @TempDir /* default */ Path dir;

    @Override
    protected WorkflowDefinitionStore create() {
      final SessionConfigProperties props = new SessionConfigProperties();
      props.setBaseDir(dir.toString());
      return new FileWorkflowDefinitionStore(props, MAPPER);
    }
  }

  @Nested
  @NoArgsConstructor
  public class InMemorySessions extends SessionConfigStoreContract {
    @Override
    protected SessionConfigStore create() {
      return new InMemorySessionConfigStore(
          new SessionConfigProperties(), new InMemoryWorkflowDefinitionStore());
    }
  }

  @Nested
  @NoArgsConstructor
  public class FileSessions extends SessionConfigStoreContract {
    @TempDir /* default */ Path dir;

    @Override
    protected SessionConfigStore create() {
      final SessionConfigProperties props = new SessionConfigProperties();
      props.setBaseDir(dir.toString());
      return new FileSessionConfigStore(props, MAPPER, new InMemoryWorkflowDefinitionStore());
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
