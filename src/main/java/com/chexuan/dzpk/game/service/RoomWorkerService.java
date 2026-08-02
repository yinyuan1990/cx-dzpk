package com.chexuan.dzpk.game.service;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.*;

/**
 * 房间任务调度 — 同 roomId 任务串行执行,不同房间并行。
 * 复制自扯旋主服 RoomWorkerService(其本身移植自老德州 WorkThreadService)。
 */
@Slf4j
@Service
public class RoomWorkerService {

    private static final int POOL_THREADS = 32;
    private static final int SCHEDULE_THREADS = 8;

    private final StripedExecutorService pool;
    private final ScheduledExecutorService schedulePool;
    private final Map<Long, Object> stripeMap = new ConcurrentHashMap<>();

    public RoomWorkerService() {
        this.pool = new StripedExecutorService(POOL_THREADS);
        this.schedulePool = Executors.newScheduledThreadPool(SCHEDULE_THREADS, r -> {
            Thread t = new Thread(r, "dzpk-room-scheduler");
            t.setDaemon(true);
            return t;
        });
    }

    private Object getStripe(Long roomId) {
        return stripeMap.computeIfAbsent(roomId, k -> new Object());
    }

    /** 提交房间任务(立即执行,同房间串行) */
    public void submit(Long roomId, Runnable task) {
        pool.submit(new RoomTask(getStripe(roomId), task));
    }

    /** 提交延迟房间任务(delay 后进入串行队列) */
    public ScheduledFuture<?> submitDelay(Long roomId, Runnable task, long delay, TimeUnit unit) {
        Object stripe = getStripe(roomId);
        return schedulePool.schedule(() -> pool.submit(new RoomTask(stripe, task)), delay, unit);
    }

    public ScheduledFuture<?> submitDelaySecs(Long roomId, Runnable task, int delaySecs) {
        return submitDelay(roomId, task, delaySecs, TimeUnit.SECONDS);
    }

    public ScheduledFuture<?> submitDelayMs(Long roomId, Runnable task, long delayMs) {
        return submitDelay(roomId, task, delayMs, TimeUnit.MILLISECONDS);
    }

    /** 房间销毁时清理 stripe,防内存泄漏 */
    public void removeRoom(Long roomId) {
        stripeMap.remove(roomId);
    }

    @PreDestroy
    public void shutdown() {
        log.info("关闭德州房间任务线程池...");
        schedulePool.shutdown();
        pool.shutdown();
        try {
            schedulePool.awaitTermination(5, TimeUnit.SECONDS);
            pool.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static class RoomTask implements Runnable, StripedExecutorService.StripedObject {
        private final Object stripe;
        private final Runnable delegate;

        RoomTask(Object stripe, Runnable delegate) {
            this.stripe = stripe;
            this.delegate = delegate;
        }

        @Override
        public Object getStripe() {
            return stripe;
        }

        @Override
        public void run() {
            delegate.run();
        }
    }
}
