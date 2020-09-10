/*
 * Copyright © 2020 camunda services GmbH (info@camunda.com)
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
package io.atomix.raft;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.atomix.cluster.ClusterMembershipService;
import io.atomix.cluster.MemberId;
import io.atomix.raft.impl.RaftContext;
import io.atomix.raft.partition.impl.RaftNamespaces;
import io.atomix.raft.protocol.ControllableRaftServerProtocol;
import io.atomix.raft.protocol.RaftMessage;
import io.atomix.raft.roles.LeaderRole;
import io.atomix.raft.snapshot.TestSnapshotStore;
import io.atomix.raft.storage.RaftStorage;
import io.atomix.raft.zeebe.NoopEntryValidator;
import io.atomix.raft.zeebe.ZeebeLogAppender.AppendListener;
import io.atomix.storage.StorageLevel;
import io.atomix.storage.journal.JournalReader;
import io.atomix.storage.journal.JournalReader.Mode;
import io.zeebe.util.collection.Tuple;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jmock.lib.concurrent.DeterministicScheduler;
import org.junit.After;
import org.junit.Before;
import org.junit.rules.ExternalResource;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;

public class RaftContextRule extends ExternalResource {

  public TemporaryFolder temporaryFolder = new TemporaryFolder();

  private final Map<MemberId, ControllableRaftServerProtocol> serverProtocols = new HashMap<>();
  private final Map<MemberId, Queue<Tuple<RaftMessage, Tuple<Runnable, CompletableFuture>>>>
      messageQueue = new HashMap<>();
  private final Map<MemberId, DeterministicSingleThreadContext> deterministicExecutors =
      new HashMap<>();
  private Path directory;

  private final int nodeCount;
  private final Map<MemberId, RaftContext> raftServers = new HashMap<>();
  private Duration electionTimeout;
  private Duration hearbeatTimeout;
  private int nextEntry = 0;
  // Used only for verification
  private final Map<Long, MemberId> leadersAtTerms = new HashMap<>();

  public RaftContextRule(final int nodeCount) {
    this.nodeCount = nodeCount;
  }

  public Map<MemberId, RaftContext> getRaftServers() {
    return raftServers;
  }

  public RaftContext getRaftServer(final int memberId) {
    return raftServers.get(MemberId.from(String.valueOf(memberId)));
  }

  public RaftContext getRaftServer(final MemberId memberId) {
    return raftServers.get(memberId);
  }

  @Override
  public Statement apply(final Statement base, final Description description) {
    final var statement = super.apply(base, description);
    return temporaryFolder.apply(statement, description);
  }

  @Before
  public void before()
      throws IOException, InterruptedException, ExecutionException, TimeoutException {
    directory = temporaryFolder.newFolder().toPath();
    if (nodeCount > 0) {
      createRaftContexts(nodeCount);
    }
    joinRaftServers();
    electionTimeout = getRaftServer(0).getElectionTimeout();
    hearbeatTimeout = getRaftServer(0).getHeartbeatInterval();

    // expecting 0 to be the leader
    tickHeartbeatTimeout(0);
  }

  @After
  public void after() {
    raftServers.forEach((m, c) -> c.close());
    raftServers.clear();
    serverProtocols.clear();
    deterministicExecutors.forEach((m, e) -> e.close());
    deterministicExecutors.clear();
  }

  private void joinRaftServers() throws InterruptedException, ExecutionException, TimeoutException {
    final Set<CompletableFuture<Void>> futures = new HashSet<>();
    final var servers = getRaftServers();
    final var serverIds = new ArrayList<>(servers.keySet());
    final long electionTimeout =
        servers.get(MemberId.from(String.valueOf(0))).getElectionTimeout().toMillis();
    Collections.sort(serverIds);
    servers.forEach(
        (memberId, raftContext) -> {
          futures.add(raftContext.getCluster().bootstrap(serverIds));
        });

    runUntilDone(0);
    // trigger election on 0
    getDeterministicScheduler(MemberId.from(String.valueOf(0)))
        .tick(2 * electionTimeout, TimeUnit.MILLISECONDS);
    final var joinFuture = CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new));
    while (!joinFuture.isDone()) {
      runUntilDone();
    }
    joinFuture.get(1, TimeUnit.SECONDS);
  }

  private void createRaftContexts(final int nodeCount) {
    for (int i = 0; i < nodeCount; i++) {
      final var memberId = MemberId.from(String.valueOf(i));
      raftServers.put(memberId, createRaftContext(memberId));
    }
  }

  public RaftContext createRaftContext(final MemberId memberId) {
    final var raft =
        new RaftContext(
            memberId.id() + "-partition-1",
            memberId,
            mock(ClusterMembershipService.class),
            new ControllableRaftServerProtocol(memberId, serverProtocols, messageQueue),
            createStorage(memberId),
            (f, u) ->
                deterministicExecutors.computeIfAbsent(
                    memberId,
                    m ->
                        (DeterministicSingleThreadContext)
                            DeterministicSingleThreadContext.createContext(f, u)));
    raft.setEntryValidator(new NoopEntryValidator());
    return raft;
  }

  private RaftStorage createStorage(final MemberId memberId) {
    return createStorage(memberId, Function.identity());
  }

  private RaftStorage createStorage(
      final MemberId memberId,
      final Function<RaftStorage.Builder, RaftStorage.Builder> configurator) {
    final var memberDirectory = getMemberDirectory(directory, memberId.toString());
    final RaftStorage.Builder defaults =
        RaftStorage.builder()
            .withStorageLevel(StorageLevel.DISK)
            .withDirectory(memberDirectory)
            .withMaxEntriesPerSegment(10)
            .withMaxSegmentSize(1024 * 10)
            .withFreeDiskSpace(100)
            .withSnapshotStore(new TestSnapshotStore(new AtomicReference<>()))
            .withNamespace(RaftNamespaces.RAFT_STORAGE);
    return configurator.apply(defaults).build();
  }

  private File getMemberDirectory(final Path directory, final String s) {
    return new File(directory.toFile(), s);
  }

  public ControllableRaftServerProtocol getServerProtocol(final MemberId memberId) {
    return serverProtocols.get(memberId);
  }

  public ControllableRaftServerProtocol getServerProtocol(final int memberId) {
    return getServerProtocol(MemberId.from(String.valueOf(memberId)));
  }

  public DeterministicScheduler getDeterministicScheduler(final MemberId memberId) {
    return deterministicExecutors.get(memberId).getDeterministicScheduler();
  }

  public DeterministicScheduler getDeterministicScheduler(final int memberId) {
    return getDeterministicScheduler(MemberId.from(String.valueOf(memberId)));
  }

  public void tickElectionTimeout(final int memberId) {
    getDeterministicScheduler(memberId).tick(electionTimeout.toMillis(), TimeUnit.MILLISECONDS);
  }

  public void tickElectionTimeout(final MemberId memberId) {
    getDeterministicScheduler(memberId).tick(electionTimeout.toMillis(), TimeUnit.MILLISECONDS);
  }

  public void runUntilDone() {
    final var serverIds = raftServers.keySet();
    processAllMessage();
    serverIds.forEach(memberId -> getDeterministicScheduler(memberId).runUntilIdle());
  }

  public void processAllMessage() {
    final var serverIds = raftServers.keySet();
    serverIds.forEach(memberId -> getServerProtocol(memberId).receiveAll());
  }

  public void processAllMessage(final MemberId memberId) {
    getServerProtocol(memberId).receiveAll();
  }

  public void processNextMessage(final MemberId memberId) {
    getServerProtocol(memberId).receiveNextMessage();
  }

  public void runUntilDone(final int memberId) {
    getServerProtocol(memberId).receiveAll();
    getDeterministicScheduler(memberId).runUntilIdle();
  }

  public void runUntilDone(final MemberId memberId) {
    getServerProtocol(memberId).receiveAll();
    getDeterministicScheduler(memberId).runUntilIdle();
  }

  public void runNextTask(final MemberId memberId) {
    getServerProtocol(memberId).receiveAll();
    final var scheduler = getDeterministicScheduler(memberId);
    if (!scheduler.isIdle()) {
      scheduler.runNextPendingCommand();
    }
  }

  public void tickHeartbeatTimeout(final int memberId) {
    getDeterministicScheduler(memberId).tick(hearbeatTimeout.toMillis(), TimeUnit.MILLISECONDS);
  }

  public void tickHeartbeatTimeout(final MemberId memberId) {
    getDeterministicScheduler(memberId).tick(hearbeatTimeout.toMillis(), TimeUnit.MILLISECONDS);
  }

  public void clientAppend(final MemberId memberId) {
    final var role = getRaftServer(memberId).getRaftRole();
    if (role instanceof LeaderRole) {
      final ByteBuffer data = ByteBuffer.allocate(Integer.BYTES).putInt(0, nextEntry++);
      final LeaderRole leaderRole = (LeaderRole) role;
      leaderRole.appendEntry(0, 1, data, mock(AppendListener.class));
    }
  }

  public void clientAppendOnLeader() {
    final var leaderTerm = leadersAtTerms.keySet().stream().max(Long::compareTo);
    final var leader = leadersAtTerms.get(leaderTerm);
    if (leader != null) {
      clientAppend(leader);
    }
  }

  public void assertAllLogsEqual() {
    final var readers =
        raftServers.values().stream()
            .map(s -> s.getLog().openReader(0, Mode.COMMITS))
            .collect(Collectors.toList());
    long index = 0;
    while (true) {
      final var entries =
          readers.stream().filter(r -> r.hasNext()).map(r -> r.next()).collect(Collectors.toList());
      if (entries.size() == 0) {
        break;
      }
      assertThat(entries.stream().distinct().count()).isEqualTo(1);
      index++;
    }
    final var commitIndexOnLeader =
        raftServers.values().stream()
            .map(RaftContext::getCommitIndex)
            .max(Long::compareTo)
            .orElseThrow();
    assertThat(index).isEqualTo(commitIndexOnLeader);
    readers.forEach(JournalReader::close);
  }

  public void assertOnlyOneLeader() {
    raftServers.values().forEach(s -> updateAndVerifyLeaderTerm(s));
  }

  private void updateAndVerifyLeaderTerm(final RaftContext s) {
    final long term = s.getTerm();
    if (s.getLeader() != null) {
      final var leader = s.getLeader().memberId();
      if (leadersAtTerms.containsKey(term)) {
        assertThat(leadersAtTerms.get(term)).isEqualTo(leader);
      } else {
        leadersAtTerms.put(term, leader);
      }
    }
  }
}