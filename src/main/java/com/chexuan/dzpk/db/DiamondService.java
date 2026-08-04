package com.chexuan.dzpk.db;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;

/**
 * 钻石 — 德州独立货币,本体在本库 dz_user.diamond(账号体系已与扯旋脱钩,后续联动另说)。
 * 扣减用「UPDATE ... WHERE diamond >= ?」原子条件更新;变动记 dz_diamond_log 流水。
 * 游客/机器人(id >= 8亿)没有账号,一律余额 0、不可扣。
 */
@Slf4j
@Service
public class DiamondService {

    /** 8亿以上是临时 id 段(机器人 8亿/游客 9亿),dz_user 无此账号 */
    public static final long LOCAL_ID_BASE = 800_000_000L;

    private final JdbcTemplate jdbc;

    @Value("${dzpk.diamond-user-table:dz_user}")
    private String userTable;

    public DiamondService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean hasMainAccount(long userId) {
        return userId > 0 && userId < LOCAL_ID_BASE;
    }

    /** 钻石余额;游客/机器人/查询失败返回 0 */
    public long balance(long userId) {
        if (jdbc == null || !hasMainAccount(userId)) return 0;
        try {
            Long v = jdbc.query("SELECT diamond FROM " + userTable + " WHERE id = ?",
                    rs -> rs.next() ? rs.getLong(1) : null, userId);
            return v != null ? v : 0;
        } catch (Exception e) {
            log.error("钻石余额查询失败: userId={}", userId, e);
            return 0;
        }
    }

    /** 主服头像 URL;游客/机器人/无此列(开发 H2 最小表)返回 null */
    public String avatar(long userId) {
        if (jdbc == null || !hasMainAccount(userId)) return null;
        try {
            return jdbc.query("SELECT avatar FROM " + userTable + " WHERE id = ?",
                    rs -> rs.next() ? rs.getString(1) : null, userId);
        } catch (Exception e) {
            return null;
        }
    }

    /** 扣钻石(原子:余额不足不扣,返回 false)+ 记流水 */
    public boolean debit(long userId, long amount, String type, String remark) {
        if (amount <= 0) return true;
        if (jdbc == null || !hasMainAccount(userId)) return false;
        try {
            int n = jdbc.update("UPDATE " + userTable + " SET diamond = diamond - ? WHERE id = ? AND diamond >= ?",
                    amount, userId, amount);
            if (n <= 0) return false;
            logChange(userId, -amount, type, remark);
            return true;
        } catch (Exception e) {
            log.error("扣钻石失败: userId={}, amount={}, type={}", userId, amount, type, e);
            return false;
        }
    }

    /** 加钻石 + 记流水 */
    public boolean credit(long userId, long amount, String type, String remark) {
        if (amount <= 0) return true;
        if (jdbc == null || !hasMainAccount(userId)) return false;
        try {
            int n = jdbc.update("UPDATE " + userTable + " SET diamond = diamond + ? WHERE id = ?", amount, userId);
            if (n <= 0) return false;
            logChange(userId, amount, type, remark);
            return true;
        } catch (Exception e) {
            log.error("加钻石失败: userId={}, amount={}, type={}", userId, amount, type, e);
            return false;
        }
    }

    private void logChange(long userId, long amount, String type, String remark) {
        try {
            jdbc.update("INSERT INTO dz_diamond_log (user_id, amount, balance_after, type, remark, created_at) " +
                            "VALUES (?,?,?,?,?,?)",
                    userId, amount, balance(userId), type, remark == null ? "" : remark,
                    new Timestamp(System.currentTimeMillis()));
        } catch (Exception e) {
            log.error("钻石流水写入失败: userId={}", userId, e);
        }
    }
}
