package com.chexuan.dzpk.robot;

import com.chexuan.dzpk.game.model.DzRoom;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 机器人参数中心(对齐扯旋 RobotClubConfig + 房间覆盖两层结构):
 *   俱乐部级默认  → dz_robot_club_config 表(每俱乐部一条,管理台读写,带 5s 缓存);
 *   房间级覆盖    → 内存 Map(管理台热改,null=跟俱乐部走;房间销毁即清);
 *   读取回退      → 房间覆盖 → 俱乐部配置 → 内置默认。
 */
@Slf4j
@Service
public class RobotParamService {

    /** 数值参数默认值(键名=表列名) */
    public static final Map<String, Long> DEFAULTS;

    static {
        Map<String, Long> d = new LinkedHashMap<>();
        d.put("min_action_delay_ms", 800L);       // 行动最小延时
        d.put("max_action_delay_ms", 2500L);      // 行动最大延时
        d.put("aggressive_prob", 30L);            // 性格分布:松凶 %
        d.put("conservative_prob", 30L);          // 性格分布:紧弱 %(平衡=其余)
        d.put("period_win_standup_prob", 40L);    // 周期结算净赢站起概率 %
        d.put("period_lose_standup_prob", 30L);   // 周期结算净输站起概率 %
        d.put("chip_cap_multiplier", 0L);         // 筹码封顶=大盲×N,超了站起;0=不启用
        d.put("loss_cap_multiplier", 0L);         // 亏损封顶=大盲×N,亏够站起;0=不启用
        d.put("profit_enabled", 0L);              // 控盘开关(0/1)
        d.put("profit_target", 0L);               // absolute 模式目标(分,负=放水)
        d.put("profit_target_rate", 0L);          // rate 模式目标 %(如 -5 = 放水真人流水的 5%)
        d.put("profit_per_hand_cap", 0L);         // 单手推动上限(分);0=大盲×200
        d.put("profit_adjust_strength", 50L);     // 纠偏强度 %(budget≈gap×strength)
        DEFAULTS = Map.copyOf(d);
    }

    private static final long CACHE_TTL_MS = 5000;

    private final JdbcTemplate jdbc;

    private record CacheEntry(Map<String, Long> cfg, String mode, long at) { }

    private final Map<Long, CacheEntry> clubCache = new ConcurrentHashMap<>();
    /** roomId → (key → 覆盖值) */
    private final Map<Long, Map<String, Long>> roomOverrides = new ConcurrentHashMap<>();

    public RobotParamService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ==================== 读取(回退链) ====================

    public long getLong(DzRoom room, String key) {
        if (room != null) {
            Map<String, Long> ov = roomOverrides.get(room.getRoomId());
            if (ov != null) {
                Long v = ov.get(key);
                if (v != null) return v;
            }
        }
        return clubLong(room != null ? room.getClubId() : 0, key);
    }

    public int getInt(DzRoom room, String key) {
        return (int) getLong(room, key);
    }

    public boolean getBool(DzRoom room, String key) {
        return getLong(room, key) != 0;
    }

    public long clubLong(long clubId, String key) {
        Long def = DEFAULTS.get(key);
        long fallback = def == null ? 0 : def;
        if (clubId <= 0) return fallback;
        Long v = loadClub(clubId).cfg.get(key);
        return v != null ? v : fallback;
    }

    /** 控盘模式(absolute|rate,仅俱乐部级) */
    public String profitMode(long clubId) {
        if (clubId <= 0) return "absolute";
        String m = loadClub(clubId).mode;
        return m == null || m.isBlank() ? "absolute" : m;
    }

    private CacheEntry loadClub(long clubId) {
        CacheEntry e = clubCache.get(clubId);
        long now = System.currentTimeMillis();
        if (e != null && now - e.at < CACHE_TTL_MS) return e;
        Map<String, Long> cfg = new LinkedHashMap<>();
        String mode = "absolute";
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT * FROM dz_robot_club_config WHERE club_id = ?", clubId);
            if (!rows.isEmpty()) {
                Map<String, Object> row = rows.get(0);
                for (String key : DEFAULTS.keySet()) {
                    Object v = col(row, key);
                    if (v instanceof Number n) cfg.put(key, n.longValue());
                }
                Object m = col(row, "profit_mode");
                if (m != null) mode = String.valueOf(m);
            }
        } catch (Exception ex) {
            log.warn("机器人俱乐部配置读取失败: clubId={}, {}", clubId, ex.getMessage());
        }
        e = new CacheEntry(cfg, mode, now);
        clubCache.put(clubId, e);
        return e;
    }

    private static Object col(Map<String, Object> row, String key) {
        Object v = row.get(key);
        return v != null ? v : row.get(key.toUpperCase());
    }

    // ==================== 管理台:俱乐部配置读写 ====================

    /** 俱乐部配置全量(含默认值兜底,管理台表单直用) */
    public Map<String, Object> clubConfig(long clubId) {
        CacheEntry e = loadClub(clubId);
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Long> d : DEFAULTS.entrySet()) {
            out.put(d.getKey(), e.cfg.getOrDefault(d.getKey(), d.getValue()));
        }
        out.put("profit_mode", e.mode);
        return out;
    }

    /** 保存俱乐部配置(UPSERT;只收白名单键) */
    public void saveClub(long clubId, Map<String, Object> body) {
        Map<String, Long> vals = new LinkedHashMap<>();
        for (String key : DEFAULTS.keySet()) {
            Object v = body.get(key);
            if (v != null) {
                try {
                    vals.put(key, Long.parseLong(String.valueOf(v)));
                } catch (NumberFormatException ignore) { }
            }
        }
        String mode = body.get("profit_mode") != null
                && "rate".equalsIgnoreCase(String.valueOf(body.get("profit_mode"))) ? "rate" : "absolute";

        // 先补全默认(第一次保存建整行)
        Map<String, Object> current = clubConfig(clubId);
        for (String key : DEFAULTS.keySet()) {
            vals.putIfAbsent(key, ((Number) current.get(key)).longValue());
        }

        StringBuilder set = new StringBuilder();
        Object[] args = new Object[vals.size() + 3];
        int i = 0;
        for (Map.Entry<String, Long> en : vals.entrySet()) {
            if (set.length() > 0) set.append(", ");
            set.append(en.getKey()).append(" = ?");
            args[i++] = en.getValue();
        }
        set.append(", profit_mode = ?, updated_at = ?");
        args[i++] = mode;
        args[i++] = new Timestamp(System.currentTimeMillis());
        args[i] = clubId;
        int n = jdbc.update("UPDATE dz_robot_club_config SET " + set + " WHERE club_id = ?", args);
        if (n == 0) {
            StringBuilder cols = new StringBuilder("club_id");
            StringBuilder qs = new StringBuilder("?");
            Object[] ins = new Object[vals.size() + 3];
            int j = 0;
            ins[j++] = clubId;
            for (Map.Entry<String, Long> en : vals.entrySet()) {
                cols.append(", ").append(en.getKey());
                qs.append(", ?");
                ins[j++] = en.getValue();
            }
            cols.append(", profit_mode, updated_at");
            qs.append(", ?, ?");
            ins[j++] = mode;
            ins[j] = new Timestamp(System.currentTimeMillis());
            jdbc.update("INSERT INTO dz_robot_club_config (" + cols + ") VALUES (" + qs + ")", ins);
        }
        clubCache.remove(clubId);
        log.info("机器人俱乐部配置保存: clubId={}, mode={}", clubId, mode);
    }

    // ==================== 管理台:房间覆盖 ====================

    /** 设置房间覆盖(null/空值键=清除该键覆盖) */
    public void setRoomOverrides(long roomId, Map<String, Object> body) {
        Map<String, Long> ov = roomOverrides.computeIfAbsent(roomId, k -> new ConcurrentHashMap<>());
        for (String key : DEFAULTS.keySet()) {
            if (!body.containsKey(key)) continue;
            Object v = body.get(key);
            if (v == null || String.valueOf(v).isBlank()) {
                ov.remove(key);
            } else {
                try {
                    ov.put(key, Long.parseLong(String.valueOf(v)));
                } catch (NumberFormatException ignore) { }
            }
        }
        if (ov.isEmpty()) roomOverrides.remove(roomId);
        log.info("机器人房间覆盖更新: roomId={}, overrides={}", roomId, ov);
    }

    public void clearRoom(long roomId) {
        roomOverrides.remove(roomId);
    }

    /** 房间实际生效参数(值+来源,管理台"这桌到底用哪套"展示;对齐扯旋 /roomParams) */
    public Map<String, Object> effective(DzRoom room) {
        Map<String, Long> ov = room != null ? roomOverrides.getOrDefault(room.getRoomId(), Map.of()) : Map.of();
        long clubId = room != null ? room.getClubId() : 0;
        Map<String, Object> out = new LinkedHashMap<>();
        for (String key : DEFAULTS.keySet()) {
            Map<String, Object> item = new LinkedHashMap<>();
            Long o = ov.get(key);
            item.put("value", o != null ? o : clubLong(clubId, key));
            item.put("source", o != null ? "房间覆盖" : "俱乐部默认");
            item.put("override", o);
            out.put(key, item);
        }
        out.put("profit_mode", Map.of("value", profitMode(clubId), "source", "俱乐部默认"));
        return out;
    }
}
