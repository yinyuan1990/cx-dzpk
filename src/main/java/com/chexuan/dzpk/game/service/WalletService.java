package com.chexuan.dzpk.game.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 德州金币钱包(带入货币,按游戏独立) — dz_user_wallet 落库:
 *   扣款「UPDATE ... WHERE balance >= ?」原子条件更新,重启不丢;
 *   首次访问自动开户(guest-init-balance,上线接主服积分后置 0)。
 * 测试用 new WalletService() → 纯内存,不依赖数据库。
 */
@Slf4j
@Service
public class WalletService {

    private final JdbcTemplate jdbc;

    /** 内存兜底(jdbc == null 时,单测用) */
    private final Map<Long, AtomicLong> memBalances = new ConcurrentHashMap<>();

    @Value("${dzpk.guest-init-balance:1000000}")
    private long initBalance;

    /** 系统参数中心(可为 null,退回 @Value 默认) */
    private final com.chexuan.dzpk.config.DzConfigService cfg;

    @Autowired
    public WalletService(JdbcTemplate jdbc, com.chexuan.dzpk.config.DzConfigService cfg) {
        this.jdbc = jdbc;
        this.cfg = cfg;
    }

    /** 单测用 */
    public WalletService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.cfg = null;
    }

    /** 单测用:纯内存 */
    public WalletService() {
        this((JdbcTemplate) null);
    }

    private long initBalance() {
        return cfg != null ? cfg.getLong("guest_init_balance", initBalance) : initBalance;
    }

    public long balance(long userId) {
        if (jdbc == null) {
            return memBalances.computeIfAbsent(userId, k -> new AtomicLong(initBalance)).get();
        }
        ensureRow(userId);
        Long v = jdbc.query("SELECT balance FROM dz_user_wallet WHERE user_id = ?",
                rs -> rs.next() ? rs.getLong(1) : null, userId);
        return v != null ? v : 0;
    }

    /** 扣款,余额不足返回 false */
    public boolean debit(long userId, long amount) {
        if (amount <= 0) return true;
        if (jdbc == null) {
            AtomicLong bal = memBalances.computeIfAbsent(userId, k -> new AtomicLong(initBalance));
            while (true) {
                long cur = bal.get();
                if (cur < amount) return false;
                if (bal.compareAndSet(cur, cur - amount)) return true;
            }
        }
        ensureRow(userId);
        return jdbc.update("UPDATE dz_user_wallet SET balance = balance - ?, updated_at = ? " +
                "WHERE user_id = ? AND balance >= ?", amount, now(), userId, amount) > 0;
    }

    public void credit(long userId, long amount) {
        if (amount <= 0) return;
        if (jdbc == null) {
            memBalances.computeIfAbsent(userId, k -> new AtomicLong(initBalance)).addAndGet(amount);
            return;
        }
        ensureRow(userId);
        jdbc.update("UPDATE dz_user_wallet SET balance = balance + ?, updated_at = ? WHERE user_id = ?",
                amount, now(), userId);
    }

    /** 首次访问自动开户(并发重复插入靠主键约束,冲突忽略) */
    private void ensureRow(long userId) {
        try {
            Integer exists = jdbc.query("SELECT 1 FROM dz_user_wallet WHERE user_id = ?",
                    rs -> rs.next() ? 1 : null, userId);
            if (exists == null) {
                jdbc.update("INSERT INTO dz_user_wallet (user_id, balance, updated_at) VALUES (?,?,?)",
                        userId, initBalance(), now());
                log.info("钱包开户: userId={}, init={}", userId, initBalance());
            }
        } catch (Exception e) {
            // 并发下主键冲突可忽略
            log.debug("钱包开户冲突(可忽略): userId={}, {}", userId, e.getMessage());
        }
    }

    private static Timestamp now() {
        return new Timestamp(System.currentTimeMillis());
    }
}
