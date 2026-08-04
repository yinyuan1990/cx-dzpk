package com.chexuan.dzpk.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 系统参数中心(对齐扯旋 system_config):
 *   默认值定义在代码里 → 启动播种进 dz_system_config(缺哪行补哪行)→ 全量载入内存缓存;
 *   管理后台改参数 → 落库 + 更新缓存,业务每次读缓存,改了立即生效,不用重启。
 *   jdbc=null(单测)时退化为纯内存,set/get 照常可用。
 */
@Slf4j
@Service
public class DzConfigService {

    /** 一条参数的定义:默认值 + 分组 + 说明 */
    public record Def(String defValue, String group, String remark) {
    }

    /** 全部可调参数(key 用下划线;分组给管理后台归类展示) */
    public static final Map<String, Def> DEFS = new LinkedHashMap<>();

    static {
        // ---- 牌局 ----
        DEFS.put("action_timeout_secs", new Def("15", "牌局", "行动思考时间(秒),建房未单独配时生效"));
        DEFS.put("next_hand_delay_secs", new Def("4", "牌局", "一手结束到下一手开始的间隔(秒)"));
        DEFS.put("await_buyin_secs", new Def("10", "牌局", "周期结算/打光后补带入等待(秒),超时自动站起"));
        DEFS.put("insurance_timeout_secs", new Def("12", "牌局", "保险决策超时(秒)"));
        // ---- 建房参数(可选档,逗号分隔;游戏端建房表单与钻石矩阵行列都由这两组派生,对齐扯旋v50/v51) ----
        DEFS.put("room_settle_time_options", new Def("30,45,60,90,120", "建房参数", "可选结算时长(分钟,逗号分隔)"));
        DEFS.put("room_blind_options", new Def("50,100,250,500,1000", "建房参数", "可选小盲(逗号分隔;大盲恒=小盲×2)"));
        DEFS.put("room_op_time_options", new Def("10,15,20,30", "建房参数", "可选思考时间(秒,逗号分隔)"));
        DEFS.put("room_max_rate_options", new Def("2,4,10", "建房参数", "可选最大带入倍数(带入上限=100大盲×倍数)"));
        DEFS.put("room_min_time_options", new Def("0,30,60", "建房参数", "可选最短上桌时间(分钟,0=不限)"));
        DEFS.put("room_rake_percent_options", new Def("0,3,5,10", "建房参数", "可选抽水比例%(对盈利抽)"));
        DEFS.put("room_rake_max", new Def("5", "建房参数", "费率拖动条上限%(对齐Unity 0~5,大厅房强制0)"));
        DEFS.put("room_in_rate_max", new Def("8", "建房参数", "带入倍数区间上限(对齐Unity双把手1~8)"));
        // GPS 防火牌(对齐扯旋 gps_min_distance_meters / gps_max_age_seconds)
        DEFS.put("gps_min_distance_m", new Def("100", "系统", "GPS防火牌:同桌玩家最小距离(米),开了GPS限制的桌坐下时校验"));
        DEFS.put("gps_max_age_secs", new Def("90", "系统", "GPS防火牌:定位数据最大有效期(秒),过期视为未上报"));
        // ---- 留座暂离 ----
        DEFS.put("seat_reserve_grace_secs", new Def("300", "留座暂离", "放假倒计时(秒),每周期一次,超时自动站起"));
        DEFS.put("seat_lock_secs", new Def("480", "留座暂离", "放假超时站起后座位物理保留(秒)"));
        // ---- 离桌罚金 ----
        DEFS.put("winner_early_leave_rate", new Def("30", "离桌罚金", "赢家早退过路费率%(俱乐部房,归群主)"));
        DEFS.put("run_away_enabled", new Def("0", "离桌罚金", "逃跑罚金开关(1开0关)"));
        DEFS.put("run_away_time_mins", new Def("6", "离桌罚金", "逃跑罚金:累计离线阈值(分钟)"));
        DEFS.put("run_away_penalty_rate", new Def("30", "离桌罚金", "逃跑罚金率%"));
        // ---- 钻石 ----
        DEFS.put("owner_period_diamond_cost", new Def("5", "钻石", "圈主周期服务费兜底值(档位矩阵没匹配到时用,0=不扣)"));
        DEFS.put("owner_period_diamond_tiers", new Def("[]", "钻石",
                "圈主周期扣钻档位矩阵(对齐扯旋v51):JSON数组,按 结算分钟+大盲 精确匹配。" +
                        "例 [{\"minutes\":30,\"baseScore\":100,\"cost\":10},{\"minutes\":60,\"baseScore\":200,\"cost\":30}]"));
        DEFS.put("create_room_diamond_cost", new Def("0", "钻石", "创建房间扣创建者钻石(0=不扣)"));
        DEFS.put("create_club_diamond_cost", new Def("0", "钻石", "创建俱乐部扣钻石(0=不扣)"));
        DEFS.put("register_init_diamond", new Def("0", "钻石", "新注册账号赠送钻石(独立账号体系,0=不送)"));
        DEFS.put("register_nickname_max_length", new Def("4", "系统", "注册昵称最大长度(汉字宽度,对标扯旋)"));
        // ---- 俱乐部/钱包 ----
        DEFS.put("max_club_per_user", new Def("10", "俱乐部", "每人最多创建俱乐部数"));
        DEFS.put("club_name_max_length", new Def("4", "俱乐部", "俱乐部名称最大长度(汉字宽度,半角字符算半个)"));
        DEFS.put("guest_init_balance", new Def("1000000", "钱包", "游客/新钱包初始金币"));
        // ---- 机器人 ----
        DEFS.put("robot_enabled", new Def("1", "机器人", "机器人陪打开关(1开0关)"));
        DEFS.put("robot_fill_count", new Def("2", "机器人", "每房间补足机器人数量"));
        DEFS.put("robot_min_delay_ms", new Def("800", "机器人", "机器人行动延迟下限(毫秒)"));
        DEFS.put("robot_max_delay_ms", new Def("2500", "机器人", "机器人行动延迟上限(毫秒)"));
        // ---- 系统 ----
        DEFS.put("maintenance_mode", new Def("0", "系统",
                "停服维护(1=开):禁止坐下/建房,空闲桌立即清场,游戏中的桌打完当前这手全员站起请离(不收罚金)。重启后自动恢复为关"));
        DEFS.put("allow_guest", new Def("1", "系统", "允许游客登录(联调用,上线关)"));
    }

    private final JdbcTemplate jdbc;
    private final Map<String, String> cache = new ConcurrentHashMap<>();

    public DzConfigService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        // 先用默认值填缓存,DB 值在 init() 覆盖
        DEFS.forEach((k, d) -> cache.put(k, d.defValue()));
    }

    @PostConstruct
    public void init() {
        if (jdbc == null) return;
        try {
            // 播种缺失行
            for (Map.Entry<String, Def> e : DEFS.entrySet()) {
                jdbc.update("INSERT INTO dz_system_config (cfg_key, cfg_value, remark, updated_at) " +
                                "SELECT ?, ?, ?, ? FROM DUAL WHERE NOT EXISTS " +
                                "(SELECT 1 FROM dz_system_config WHERE cfg_key = ?)",
                        e.getKey(), e.getValue().defValue(), e.getValue().remark(),
                        new Timestamp(System.currentTimeMillis()), e.getKey());
            }
            // 全量载入
            jdbc.query("SELECT cfg_key, cfg_value FROM dz_system_config", rs -> {
                cache.put(rs.getString(1), rs.getString(2));
            });
            // 停服开关重启自动恢复为关(对齐扯旋"后端重启后自动恢复为关闭")
            if (getBool("maintenance_mode", false)) {
                jdbc.update("UPDATE dz_system_config SET cfg_value = '0' WHERE cfg_key = 'maintenance_mode'");
                cache.put("maintenance_mode", "0");
                log.warn("检测到停服维护开关残留,重启已自动恢复为关闭");
            }
            log.info("系统参数载入完成: {} 项", cache.size());
        } catch (Exception e) {
            log.error("系统参数初始化失败,使用代码默认值", e);
        }
    }

    // ==================== 读(业务侧高频调用,纯内存) ====================

    public String getStr(String key, String def) {
        return cache.getOrDefault(key, def);
    }

    public int getInt(String key, int def) {
        try {
            return Integer.parseInt(cache.getOrDefault(key, String.valueOf(def)).trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public long getLong(String key, long def) {
        try {
            return Long.parseLong(cache.getOrDefault(key, String.valueOf(def)).trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /** 布尔:1/true 为开 */
    public boolean getBool(String key, boolean def) {
        String v = cache.get(key);
        if (v == null) return def;
        return "1".equals(v.trim()) || "true".equalsIgnoreCase(v.trim());
    }

    /** 逗号分隔的数字档位列表(建房参数选项);非法项跳过,空/全非法时退回默认串解析 */
    public List<Long> getLongList(String key, String def) {
        List<Long> out = parseLongList(cache.getOrDefault(key, def));
        return out.isEmpty() ? parseLongList(def) : out;
    }

    private static List<Long> parseLongList(String s) {
        List<Long> out = new ArrayList<>();
        if (s == null) return out;
        for (String part : s.split("[,，\\s]+")) {
            if (part.isBlank()) continue;
            try {
                out.add(Long.parseLong(part.trim()));
            } catch (NumberFormatException ignore) {
                // 跳过非法项
            }
        }
        return out;
    }

    // ==================== 写(管理后台) ====================

    /** 更新参数:未定义的 key 拒绝;落库 + 缓存,立即生效 */
    public void update(String key, String value) {
        Def def = DEFS.get(key);
        if (def == null) {
            throw new IllegalArgumentException("未知参数: " + key);
        }
        if (value == null) value = "";
        value = value.trim();
        if (value.length() > 4000) {
            throw new IllegalArgumentException("参数值过长(" + value.length() + " > 4000)");
        }
        // 数字型参数简单校验
        if (def.defValue().matches("-?\\d+") && !value.matches("-?\\d+")) {
            throw new IllegalArgumentException("参数 " + key + " 需要整数值");
        }
        if (jdbc != null) {
            jdbc.update("UPDATE dz_system_config SET cfg_value = ?, updated_at = ? WHERE cfg_key = ?",
                    value, new Timestamp(System.currentTimeMillis()), key);
        }
        cache.put(key, value);
        log.info("系统参数更新: {} = {}", key, value);
    }

    /** 单测/内部直接设值(不校验) */
    public void set(String key, String value) {
        cache.put(key, value);
    }

    /** 管理后台全量列表 */
    public List<Map<String, Object>> all() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Map.Entry<String, Def> e : DEFS.entrySet()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", e.getKey());
            m.put("value", cache.getOrDefault(e.getKey(), e.getValue().defValue()));
            m.put("def", e.getValue().defValue());
            m.put("group", e.getValue().group());
            m.put("remark", e.getValue().remark());
            list.add(m);
        }
        return list;
    }
}
