package com.chexuan.dzpk.game.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 钱包桩 — 内存余额,先把玩法跑通。
 * 后续替换为:调主服 internal 钱包接口(俱乐部积分),本类只改实现不改调用方。
 */
@Service
public class WalletService {

    private final Map<Long, AtomicLong> balances = new ConcurrentHashMap<>();

    @Value("${dzpk.guest-init-balance:1000000}")
    private long initBalance;

    public long balance(long userId) {
        return balances.computeIfAbsent(userId, k -> new AtomicLong(initBalance)).get();
    }

    /** 扣款,余额不足返回 false */
    public boolean debit(long userId, long amount) {
        AtomicLong bal = balances.computeIfAbsent(userId, k -> new AtomicLong(initBalance));
        while (true) {
            long cur = bal.get();
            if (cur < amount) return false;
            if (bal.compareAndSet(cur, cur - amount)) return true;
        }
    }

    public void credit(long userId, long amount) {
        if (amount <= 0) return;
        balances.computeIfAbsent(userId, k -> new AtomicLong(initBalance)).addAndGet(amount);
    }
}
