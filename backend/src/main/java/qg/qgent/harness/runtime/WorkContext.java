package qg.qgent.harness.runtime;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import qg.qgent.harness.entity.AgentRunEntity;
import qg.qgent.chat.event.MessageSentEvent;
import qg.qgent.workspace.service.WorkspaceSelection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Root-owned execution resources shared by the main run and its temporary children. */
final class WorkContext {
    final MessageSentEvent event;
    final UUID rootRunId;
    final Object environmentLock = new Object();
    final java.util.Set<UUID> openedWorkspaces = ConcurrentHashMap.newKeySet();
    final Map<UUID, AgentExecution> children = new ConcurrentHashMap<>();
    final Map<UUID, Object> toolsets = new ConcurrentHashMap<>();
    private final java.util.Set<UUID> cancelledCommandRuns = ConcurrentHashMap.newKeySet();
    volatile UUID defaultWorkspaceId;

    private final Object notificationLock = new Object();
    private final Map<UUID, Message> notifications = new LinkedHashMap<>();
    // Guarded by notificationLock so the final notice and zero-active state become visible together.
    private int activeChildren;
    private volatile boolean cancelled;
    private volatile boolean closing;

    WorkContext(MessageSentEvent event, UUID rootRunId) {
        this.event = event;
        this.rootRunId = rootRunId;
    }

    boolean hasChildCapacity(int limit) {
        synchronized (notificationLock) {
            return !closing && !cancelled && activeChildren < limit;
        }
    }

    boolean reserveChild(AgentExecution child, int limit) {
        synchronized (child) {
            synchronized (notificationLock) {
                if (!hasCapacity(limit)) return false;
                activeChildren++;
                child.holdReservation();
                children.put(child.run.getId(), child);
                return true;
            }
        }
    }

    boolean reserveContinuation(AgentExecution child, int limit) {
        synchronized (child) {
            synchronized (notificationLock) {
                if (!hasCapacity(limit)) return false;
                activeChildren++;
                child.holdReservation();
                cancelledCommandRuns.remove(child.run.getId());
                return true;
            }
        }
    }

    boolean publishChildFinish(AgentExecution child) {
        synchronized (child) {
            synchronized (notificationLock) {
                if ((cancelled || child.isStopRequested()) && !"CANCELLED".equals(child.run.getStatus())) {
                    return false;
                }
                String status = child.run.getStatus();
                String result = child.run.getResult() == null ? "" : child.run.getResult();
                notifications.put(child.run.getId(), new UserMessage("子 Agent " + child.run.getId()
                        + " 已结束，状态：" + status + "。执行结果：\n" + result));
                if (child.releaseReservation()) activeChildren--;
                child.finishTurn(child.run);
                notificationLock.notifyAll();
                return true;
            }
        }
    }

    boolean requestChildStop(AgentExecution child) {
        synchronized (notificationLock) {
            if (!child.isBusy() || child.isStopRequested()) return false;
            child.requestStop();
            return true;
        }
    }

    boolean markCommandsCancelled(UUID runId) {
        return cancelledCommandRuns.add(runId);
    }

    private boolean hasCapacity(int limit) {
        return !closing && !cancelled && activeChildren < limit;
    }

    AgentLoop.UpdateSource rootUpdates(BooleanSupplier rootCancelled) {
        return new AgentLoop.UpdateSource() {
            public List<Message> drain() { return drainNotifications(); }
            public List<Message> awaitNext() {
                synchronized (notificationLock) {
                    while (notifications.isEmpty() && activeChildren > 0 && !closing && !cancelled
                            && !rootCancelled.getAsBoolean()) {
                        try {
                            notificationLock.wait();
                        } catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();
                            return List.of();
                        }
                    }
                }
                return drainNotifications();
            }
        };
    }

    AgentLoop.UpdateSource childUpdates(AgentExecution child) {
        return new AgentLoop.UpdateSource() {
            public List<Message> drain() {
                List<String> messages = child.drainMessages();
                return messages.stream().map(UserMessage::new).map(Message.class::cast).toList();
            }
            public List<Message> awaitNext() { return List.of(); }
        };
    }

    private List<Message> drainNotifications() {
        synchronized (notificationLock) {
            if (notifications.isEmpty()) return List.of();
            List<Message> result = new ArrayList<>(notifications.values());
            notifications.clear();
            return result;
        }
    }

    boolean consumeNotification(UUID childRunId) {
        synchronized (notificationLock) { return notifications.remove(childRunId) != null; }
    }

    boolean hasActiveChildren() {
        synchronized (notificationLock) { return activeChildren > 0; }
    }

    void awaitChildren() {
        boolean interrupted = false;
        synchronized (notificationLock) {
            while (activeChildren > 0) {
                try { notificationLock.wait(); }
                catch (InterruptedException exception) { interrupted = true; }
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    void cancel() {
        synchronized (notificationLock) {
            cancelled = true;
            notificationLock.notifyAll();
        }
    }

    void close() {
        closing = true;
        signal();
    }

    boolean isCancelled() { return cancelled; }

    private void signal() {
        synchronized (notificationLock) { notificationLock.notifyAll(); }
    }

    static final class AgentExecution {
        final AgentRunEntity run;
        final AgentLoop.Conversation conversation;
        private final java.util.ArrayDeque<String> messages = new java.util.ArrayDeque<>();
        private CompletableFuture<AgentRunEntity> completion = new CompletableFuture<>();
        private volatile Thread worker;
        private volatile boolean busy;
        private volatile boolean stopRequested;
        private boolean reservationHeld;
        private boolean finishing;
        private boolean turnFinished;
        final WorkspaceSelection workspace;

        AgentExecution(AgentRunEntity run, AgentLoop.Conversation conversation,
                       UUID selectedWorkspace, String currentDirectory) {
            this.run = run;
            this.conversation = conversation;
            this.workspace = new WorkspaceSelection(selectedWorkspace, currentDirectory);
        }

        boolean isBusy() { return busy; }
        boolean isStopRequested() { return stopRequested; }
        synchronized boolean isFinishing() { return finishing; }
        synchronized Thread worker() { return worker; }
        synchronized CompletableFuture<AgentRunEntity> completion() { return completion; }

        synchronized void schedule() {
            busy = true;
            stopRequested = false;
            finishing = false;
            turnFinished = false;
        }

        synchronized boolean beginWorker() {
            if (!busy || stopRequested || Thread.currentThread().isInterrupted()) return false;
            worker = Thread.currentThread();
            return true;
        }

        synchronized void enqueueMessage(String message) {
            if (message != null && !message.isBlank()) messages.addLast(message);
        }

        synchronized List<String> drainMessages() {
            if (messages.isEmpty()) return List.of();
            List<String> result = new ArrayList<>(messages);
            messages.clear();
            return result;
        }

        synchronized void resumeConversation(String message) {
            stopRequested = false;
            if (message != null && !message.isBlank()) conversation.addUserMessage(message);
            completion = new CompletableFuture<>();
        }

        void requestStop() {
            stopRequested = true;
            Thread active = worker;
            if (active != null) active.interrupt();
        }

        synchronized void holdReservation() { reservationHeld = true; }
        synchronized boolean releaseReservation() {
            if (!reservationHeld) return false;
            reservationHeld = false;
            return true;
        }

        synchronized boolean beginFinishing() {
            if (turnFinished || finishing) return false;
            finishing = true;
            return true;
        }

        synchronized void finishTurn(AgentRunEntity result) {
            finishing = false;
            turnFinished = true;
            busy = false;
            worker = null;
            completion.complete(result);
            notifyAll();
        }

        AgentRunEntity await(long timeout, TimeUnit unit) {
            try { return completion().get(timeout, unit); }
            catch (java.util.concurrent.TimeoutException exception) { return null; }
            catch (InterruptedException exception) { Thread.currentThread().interrupt(); return null; }
            catch (java.util.concurrent.ExecutionException exception) {
                throw new IllegalStateException("等待子 Agent 失败", exception.getCause());
            }
        }
    }
}
