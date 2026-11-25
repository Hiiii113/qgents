package qg.qgent.memory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
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

/** 两个群同时改同一篇 Agent 私有记忆：两次读改写各自在持有项目行锁的事务里执行，改动都保留。 */
class AgentMemoryServiceConcurrencyTest {

    private static final int MAX_CHARS = 10_000;

    @TempDir Path root;

    @Test
    void simultaneousEditsFromTwoGroupsAreBothKept() throws Exception {
        Long projectId = TestIds.next();
        Long agentId = TestIds.next();
        ProjectMapper projects = mock(ProjectMapper.class);
        ReentrantLock projectRow = new ReentrantLock();
        when(projects.selectByIdForUpdate(projectId))
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
        AgentMemoryService service =
                transactional(
                        new AgentMemoryService(
                                projects, directories(), lockedDocument(projectRow)));
        service.save(projectId, agentId, "# 分工\n- 接口\n- 测试");

        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService threads = Executors.newFixedThreadPool(2)) {
            Future<?> apiGroup =
                    threads.submit(
                            () -> edit(service, start, projectId, agentId, "- 接口", "- 接口与文档"));
            Future<?> testGroup =
                    threads.submit(
                            () -> edit(service, start, projectId, agentId, "- 测试", "- 测试与发布"));
            start.countDown();
            apiGroup.get(10, TimeUnit.SECONDS);
            testGroup.get(10, TimeUnit.SECONDS);
        }

        assertThat(service.view(projectId, agentId))
                .isEqualTo("# 分工\n- 接口与文档\n- 测试与发布");
        verify(projects, times(3)).selectByIdForUpdate(projectId);
    }

    private Void edit(
            AgentMemoryService service,
            CountDownLatch start,
            Long projectId,
            Long agentId,
            String anchor,
            String replacement)
            throws InterruptedException {
        start.await();
        service.edit(projectId, agentId, anchor, replacement);
        return null;
    }

    private ProjectDirectoryService directories() {
        return new ProjectDirectoryService(root.toString(), root.toString());
    }

    /** 读改写必须发生在持有项目行锁的事务里：漏掉事务或 FOR UPDATE 时这里直接失败。 */
    private MemoryDocument lockedDocument(ReentrantLock projectRow) {
        return new MemoryDocument(MAX_CHARS) {
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
