package com.chexuan.dzpk.game.service;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * StripedExecutorService — 同 stripe 的任务串行执行,不同 stripe 可并行。
 * 复制自扯旋主服(其本身移植自老德州 WorkThreadService),两边各自演进。
 */
public class StripedExecutorService extends AbstractExecutorService {

    private final ExecutorService executor;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition terminating = lock.newCondition();
    private final Map<Object, SerialExecutor> executors = new IdentityHashMap<>();
    private static final ThreadLocal<Object> stripes = new ThreadLocal<>();
    private State state = State.RUNNING;

    private enum State {RUNNING, SHUTDOWN}

    private final int numberOfThreads;

    private StripedExecutorService(ExecutorService executor, int numberOfThreads) {
        this.executor = executor;
        this.numberOfThreads = numberOfThreads;
    }

    public StripedExecutorService(int numberOfThreads) {
        this(Executors.newFixedThreadPool(numberOfThreads, r -> {
            Thread t = new Thread(r, "dzpk-room-worker");
            t.setDaemon(true);
            return t;
        }), numberOfThreads);
    }

    public int getConfiguredThreads() {
        return numberOfThreads;
    }

    public int getActiveCount() {
        return (executor instanceof ThreadPoolExecutor tpe) ? tpe.getActiveCount() : -1;
    }

    public int getActiveStripeCount() {
        lock.lock();
        try {
            return executors.size();
        } finally {
            lock.unlock();
        }
    }

    public long getPendingTaskCount() {
        lock.lock();
        try {
            long sum = 0;
            for (SerialExecutor ser : executors.values()) {
                sum += ser.tasks.size();
            }
            return sum;
        } finally {
            lock.unlock();
        }
    }

    @Override
    protected <T> RunnableFuture<T> newTaskFor(Runnable runnable, T value) {
        saveStripedObject(runnable);
        return super.newTaskFor(runnable, value);
    }

    @Override
    protected <T> RunnableFuture<T> newTaskFor(Callable<T> callable) {
        saveStripedObject(callable);
        return super.newTaskFor(callable);
    }

    private void saveStripedObject(Object task) {
        if (task instanceof StripedObject so) {
            stripes.set(so.getStripe());
        }
    }

    @Override
    public Future<?> submit(Runnable task) {
        return submit(task, null);
    }

    @Override
    public <T> Future<T> submit(Runnable task, T result) {
        lock.lock();
        try {
            checkPoolIsRunning();
            if (task instanceof StripedObject) {
                return super.submit(task, result);
            } else {
                return executor.submit(task, result);
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public <T> Future<T> submit(Callable<T> task) {
        lock.lock();
        try {
            checkPoolIsRunning();
            if (task instanceof StripedObject) {
                return super.submit(task);
            } else {
                return executor.submit(task);
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void execute(Runnable command) {
        lock.lock();
        try {
            checkPoolIsRunning();
            Object stripe = getStripe(command);
            if (stripe != null) {
                SerialExecutor serExec = executors.get(stripe);
                if (serExec == null) {
                    executors.put(stripe, serExec = new SerialExecutor(stripe));
                }
                serExec.execute(command);
            } else {
                executor.execute(command);
            }
        } finally {
            lock.unlock();
        }
    }

    private Object getStripe(Runnable command) {
        Object stripe;
        if (command instanceof StripedObject so) {
            stripe = so.getStripe();
        } else {
            stripe = stripes.get();
        }
        stripes.remove();
        return stripe;
    }

    private void checkPoolIsRunning() {
        if (state != State.RUNNING) {
            throw new RejectedExecutionException("executor not running");
        }
    }

    @Override
    public void shutdown() {
        lock.lock();
        try {
            state = State.SHUTDOWN;
            if (executors.isEmpty()) {
                executor.shutdown();
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public List<Runnable> shutdownNow() {
        lock.lock();
        try {
            shutdown();
            List<Runnable> result = new ArrayList<>();
            for (SerialExecutor serEx : executors.values()) {
                serEx.tasks.drainTo(result);
            }
            result.addAll(executor.shutdownNow());
            return result;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean isShutdown() {
        lock.lock();
        try {
            return state == State.SHUTDOWN;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean isTerminated() {
        lock.lock();
        try {
            if (state == State.RUNNING) return false;
            for (SerialExecutor exec : executors.values()) {
                if (!exec.isEmpty()) return false;
            }
            return executor.isTerminated();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        lock.lock();
        try {
            long waitUntil = System.nanoTime() + unit.toNanos(timeout);
            long remainingTime;
            while ((remainingTime = waitUntil - System.nanoTime()) > 0 && !executors.isEmpty()) {
                terminating.awaitNanos(remainingTime);
            }
            if (remainingTime <= 0) return false;
            if (executors.isEmpty()) {
                return executor.awaitTermination(remainingTime, TimeUnit.NANOSECONDS);
            }
            return false;
        } finally {
            lock.unlock();
        }
    }

    private void removeEmptySerialExecutor(Object stripe) {
        executors.remove(stripe);
        terminating.signalAll();
        if (state == State.SHUTDOWN && executors.isEmpty()) {
            executor.shutdown();
        }
    }

    private class SerialExecutor implements Executor {
        private final BlockingQueue<Runnable> tasks = new LinkedBlockingQueue<>();
        private Runnable active;
        private final Object stripe;

        SerialExecutor(Object stripe) {
            this.stripe = stripe;
        }

        @Override
        public void execute(final Runnable r) {
            lock.lock();
            try {
                tasks.add(() -> {
                    try {
                        r.run();
                    } finally {
                        scheduleNext();
                    }
                });
                if (active == null) {
                    scheduleNext();
                }
            } finally {
                lock.unlock();
            }
        }

        private void scheduleNext() {
            lock.lock();
            try {
                if ((active = tasks.poll()) != null) {
                    executor.execute(active);
                    terminating.signalAll();
                } else {
                    removeEmptySerialExecutor(stripe);
                }
            } finally {
                lock.unlock();
            }
        }

        boolean isEmpty() {
            lock.lock();
            try {
                return active == null && tasks.isEmpty();
            } finally {
                lock.unlock();
            }
        }
    }

    /** 实现此接口的 Runnable 会按 stripe 串行执行 */
    public interface StripedObject {
        Object getStripe();
    }
}
