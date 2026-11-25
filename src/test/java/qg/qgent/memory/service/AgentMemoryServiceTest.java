package qg.qgent.memory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import qg.qgent.project.mapper.ProjectMapper;
import qg.qgent.project.storage.ProjectDirectoryService;
import qg.qgent.support.TestIds;

/** Agent 私有记忆：写在所属项目目录、按 Agent 隔离、各群共享一篇，并发改同一篇时两次改动都保留。 */
class AgentMemoryServiceTest {

    private static final int MAX_CHARS = 100;
    private static final int CONCURRENCY_MAX_CHARS = 10_000;

    @TempDir Path root;

    private final Long projectId = TestIds.next();
    private final Long agentId = TestIds.next();
    private final ProjectMapper projects = mock(ProjectMapper.class);
    private ProjectDirectoryService directories;
    private AgentMemoryService service;

    @BeforeEach
    void setUp() {
        directories = new ProjectDirectoryService(root.toString(), root.toString());
        service = new AgentMemoryService(projects, directories, new MemoryDocument(MAX_CHARS));
    }

    @Test
    void firstReadIsEmptyAndWritesNothing() {
        assertThat(service.view(projectId, agentId)).isEmpty();
        assertThat(root.resolve(projectId.toString()).resolve("agent-memory")).doesNotExist();
    }

    @Test
    void savedNotesAreReadBackAndLiveInTheProjectDirectory() throws Exception {
        service.save(projectId, agentId, "# 分工\n- 你负责接口");

        assertThat(service.view(projectId, agentId)).isEqualTo("# 分工\n- 你负责接口");
        assertThat(
                        Files.readString(
                                root.resolve(projectId.toString())
                                        .resolve("agent-memory")
                                        .resolve(agentId.toString())
                                        .resolve("memory.md"),
                                StandardCharsets.UTF_8))
                .isEqualTo("# 分工\n- 你负责接口");
    }

    @Test
    void editReplacesOnlyTheAnchorAndKeepsTheRest() {
        service.save(projectId, agentId, "# 分工\n- 你负责接口");

        service.edit(projectId, agentId, "- 你负责接口", "- 你负责接口与测试");

        assertThat(service.view(projectId, agentId)).isEqualTo("# 分工\n- 你负责接口与测试");
    }

    @Test
    void agentsAndProjectsKeepSeparateMemory() {
        Long otherAgent = TestIds.next();
        Long otherProject = TestIds.next();
        service.save(projectId, agentId, "属于这个 Agent");

        assertThat(service.view(projectId, otherAgent)).isEmpty();
        assertThat(service.view(otherProject, agentId)).isEmpty();
        assertThat(service.view(projectId, agentId)).isEqualTo("属于这个 Agent");
    }

    @Test
    void simultaneousEditsFromTwoGroupsAreBothKept() throws Exception {
        ProjectMapper rowLocks = mock(ProjectMapper.class);
        ReentrantLock projectRow = new ReentrantLock();
        when(rowLocks.selectByIdForUpdate(projectId))
                .thenAnswer(
                        invocation -> {
                            projectRow.lock();
                            // 与数据库的 FOR UPDATE 一致：事务结束才释放行锁。
                            TransactionSynchronizationManager.registerSynchronization(
                                    new TransactionSynchronization() {
                                        @Override
                                        public void afterCompletion(int status) {
                                            projectRow.unlock();
                                        }
                                    });
                            return null;
                        });
        AgentMemoryService transactionalService =
                transactional(
                        new AgentMemoryService(
                                rowLocks, directories, lockedDocument(projectRow)));
        transactionalService.save(projectId, agentId, "# 分工\n- 接口\n- 测试");

        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService threads = Executors.newFixedThreadPool(2)) {
            Future<?> apiGroup =
                    threads.submit(
                            () ->
                                    edit(
                                            transactionalService,
                                            start,
                                            "- 接口",
                                            "- 接口与文档"));
            Future<?> testGroup =
                    threads.submit(
                            () ->
                                    edit(
                                            transactionalService,
                                            start,
                                            "- 测试",
                                            "- 测试与发布"));
            start.countDown();
            apiGroup.get(10, TimeUnit.SECONDS);
            testGroup.get(10, TimeUnit.SECONDS);
        }

        assertThat(service.view(projectId, agentId))
                .isEqualTo("# 分工\n- 接口与文档\n- 测试与发布");
        verify(rowLocks, times(3)).selectByIdForUpdate(projectId);
    }

    private Void edit(
            AgentMemoryService target, CountDownLatch start, String anchor, String replacement)
            throws InterruptedException {
        start.await();
        target.edit(projectId, agentId, anchor, replacement);
        return null;
    }

    /** 读改写必须发生在持有项目行锁的事务里：漏掉事务或 FOR UPDATE 时这里直接失败。 */
    private MemoryDocument lockedDocument(ReentrantLock projectRow) {
        return new MemoryDocument(CONCURRENCY_MAX_CHARS) {
            @Override
            public String edit(Path directory, String anchor, String replacement) {
                assertThat(projectRow.isHeldByCurrentThread()).isTrue();
                return super.edit(directory, anchor, replacement);
            }
        };
    }

    private AgentMemoryService transactional(AgentMemoryService target) {
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.addAdvice(
                new TransactionInterceptor(
                        new InMemoryTransactionManager(),
                        new AnnotationTransactionAttributeSource()));
        return (AgentMemoryService) proxy.getProxy();
    }

    /** 只需要事务同步语义的内存事务管理器：行锁在 afterCompletion 释放，与数据库事务边界一致。 */
    private static final class InMemoryTransactionManager extends AbstractPlatformTransactionManager {
        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {}

        @Override
        protected void doCommit(DefaultTransactionStatus status) {}

        @Override
        protected void doRollback(DefaultTransactionStatus status) {}
    }
}
