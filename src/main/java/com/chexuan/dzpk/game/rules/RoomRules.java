package com.chexuan.dzpk.game.rules;

import lombok.Getter;
import lombok.Setter;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 建房规则参数 — 对齐老德州 /room/dz/create(DzCreationReq)的可配项。
 * 职责:解析 + 校验 + 下发(toMap);各玩法规则的开关/数值都从这里读。
 * 校验失败抛 IllegalArgumentException(msg 直接回给前端)。
 *
 * 与老德州的对应关系:
 *   sb(slmz) 小盲,大盲固定 = 小盲×2(老德州不可传大盲)
 *   maxPlayers(rpu) 2~9 | inChip(incp) 带入基数 | inMinRate/inMaxRate(inmnr/inmxr) 带入倍数
 *   opTimeSec(opts) 思考时间 | ante(qzhu) 前注 | insuranceOn(isron) 保险
 *   straddleOn(sdlon) 抓头 | muckOn(muckon) 埋牌 | vpOn(vpon) 入池率
 *   aheadLeaveOn(ahdlon) 提前离桌 | gameMinTime(gmxt) 最短上桌分钟
 *   autoStartNum(autopu) 自动开局人数 | ipLimitOn(ipon) | gpsLimitOn(gpson)
 *   jackpotOn(jpon) | delayOn(delay) 延迟看牌
 *   settleTimeMins 是本项目的循环结算周期(替代老的 gameMaxTime 散桌语义)
 */
@Getter
@Setter
public class RoomRules {

    // ==================== 基本 ====================
    private String name = "";
    /** 小盲;大盲恒为 sb*2 */
    private long sb = 50;
    /** 座位数 2~9(创建时选) */
    private int maxPlayers = 9;
    /** 循环结算周期(分钟),0=不周期结算 */
    private int settleTimeMins = 30;
    /** 抽水比例 %(盈利部分,俱乐部房分给群主/合伙人) */
    private int rakePercent = 5;
    /** 所属俱乐部(0=公开房) */
    private long clubId;

    // ==================== 带入 ====================
    /** 带入基数(老 incp);最小/最大带入 = inChip × inMinRate / inMaxRate */
    private long inChip;
    private int inMinRate = 1;
    private int inMaxRate = 4;

    // ==================== 对局 ====================
    /** 行动思考时间(秒) */
    private int opTimeSec = 15;
    /** 前注(每手开始每个参与者强制投入,直接进池) */
    private long ante;
    /** 抓头(straddle):≥3人时 BB 下家强制 2BB 盲注 */
    private boolean straddleOn;
    /** 保险:全下跑马时领先方可按 outs 赔率投保 */
    private boolean insuranceOn;
    /** 埋牌(muck):摊牌只亮赢家,输家不亮 */
    private boolean muckOn;
    /** 入池率:统计并展示 VPIP */
    private boolean vpOn;

    // ==================== 座次/离桌 ====================
    /** 自动开局人数:坐满 N 人才开局(0/1 视为 2) */
    private int autoStartNum = 2;
    /** 最短上桌时间(分钟),0=不限;未满不能站起(除非 aheadLeaveOn) */
    private int gameMinTime;
    /** 允许提前离桌(开=无视 gameMinTime) */
    private boolean aheadLeaveOn = true;
    /** 同 IP 限制同桌 */
    private boolean ipLimitOn;
    /** GPS 距离限制(参数保留,当前无定位数据不生效) */
    private boolean gpsLimitOn;

    // ==================== 平台(参数保留,玩法后续) ====================
    /** Jackpot 开关(仅金币房生效,当前仅存储下发) */
    private boolean jackpotOn;
    /** 延迟看牌(当前仅存储下发) */
    private boolean delayOn;

    public long bb() {
        return sb * 2;
    }

    public long minBuyin() {
        return inChip * inMinRate;
    }

    public long maxBuyin() {
        return inChip * inMaxRate;
    }

    /** 有效自动开局人数(最少 2) */
    public int effectiveAutoStart() {
        return Math.max(2, autoStartNum);
    }

    // ================================================================

    /** 从 CREATE_ROOM data 解析并校验;defaultName 为空名兜底 */
    public static RoomRules parse(Map<String, Object> d, String defaultName) {
        RoomRules r = new RoomRules();
        r.name = str(d, "name", "").trim();
        if (r.name.isEmpty()) r.name = defaultName;
        if (r.name.length() > 16) fail("房名最长 16 字");

        r.sb = lng(d, "sb", 50);
        if (r.sb < 1) fail("小盲至少 1");

        r.maxPlayers = (int) lng(d, "maxPlayers", 9);
        if (r.maxPlayers < 2 || r.maxPlayers > 9) fail("人数须在 2~9");

        r.settleTimeMins = (int) lng(d, "settleTimeMins", 30);
        if (r.settleTimeMins < 0 || r.settleTimeMins > 720) fail("结算时间非法");

        r.rakePercent = (int) lng(d, "rakePercent", 5);
        if (r.rakePercent < 0 || r.rakePercent > 20) fail("抽水须在 0~20%");

        r.clubId = lng(d, "clubId", 0);

        r.inChip = lng(d, "inChip", r.bb() * 100);
        if (r.inChip < r.bb() * 10) fail("带入基数至少 10 个大盲");
        r.inMinRate = (int) lng(d, "inMinRate", 1);
        r.inMaxRate = (int) lng(d, "inMaxRate", 4);
        if (r.inMinRate < 1) fail("最小带入倍数至少 1");
        if (r.inMaxRate < r.inMinRate) fail("最大带入倍数不能小于最小倍数");
        if (r.inMaxRate > 100) fail("最大带入倍数过大");

        r.opTimeSec = (int) lng(d, "opTimeSec", 15);
        if (r.opTimeSec < 5 || r.opTimeSec > 60) fail("思考时间须在 5~60 秒");

        r.ante = lng(d, "ante", 0);
        if (r.ante < 0 || r.ante > r.bb() * 2) fail("前注最多 2 个大盲");

        r.straddleOn = flag(d, "straddleOn");
        r.insuranceOn = flag(d, "insuranceOn");
        r.muckOn = flag(d, "muckOn");
        r.vpOn = flag(d, "vpOn");
        r.jackpotOn = flag(d, "jackpotOn");
        r.delayOn = flag(d, "delayOn");
        r.ipLimitOn = flag(d, "ipLimitOn");
        r.gpsLimitOn = flag(d, "gpsLimitOn");

        r.autoStartNum = (int) lng(d, "autoStartNum", 2);
        if (r.autoStartNum != 0 && (r.autoStartNum < 2 || r.autoStartNum > r.maxPlayers)) {
            fail("自动开局人数须在 2~" + r.maxPlayers);
        }

        r.gameMinTime = (int) lng(d, "gameMinTime", 0);
        if (r.gameMinTime < 0 || r.gameMinTime > 720) fail("最短上桌时间非法");
        // 未传 aheadLeaveOn 时默认允许提前离桌
        r.aheadLeaveOn = d.containsKey("aheadLeaveOn") ? flag(d, "aheadLeaveOn") : true;

        return r;
    }

    /** 下发给前端(房间列表/快照/建房回执共用) */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("sb", sb);
        m.put("bb", bb());
        m.put("maxPlayers", maxPlayers);
        m.put("settleTimeMins", settleTimeMins);
        m.put("rakePercent", rakePercent);
        m.put("clubId", clubId);
        m.put("inChip", inChip);
        m.put("inMinRate", inMinRate);
        m.put("inMaxRate", inMaxRate);
        m.put("minBuyin", minBuyin());
        m.put("maxBuyin", maxBuyin());
        m.put("opTimeSec", opTimeSec);
        m.put("ante", ante);
        m.put("straddleOn", straddleOn);
        m.put("insuranceOn", insuranceOn);
        m.put("muckOn", muckOn);
        m.put("vpOn", vpOn);
        m.put("autoStartNum", effectiveAutoStart());
        m.put("gameMinTime", gameMinTime);
        m.put("aheadLeaveOn", aheadLeaveOn);
        m.put("ipLimitOn", ipLimitOn);
        m.put("gpsLimitOn", gpsLimitOn);
        m.put("jackpotOn", jackpotOn);
        m.put("delayOn", delayOn);
        return m;
    }

    /** 测试/老接口兜底:按老默认值构一份(40~400BB 带入) */
    public static RoomRules legacy(String name, long sb, long bb, int maxPlayers,
                                   int settleTimeMins, int rakePercent) {
        RoomRules r = new RoomRules();
        r.name = name;
        r.sb = sb;
        // 老测试可能传 bb != sb*2:以 bb 半值回推 sb,保持 bb() 一致
        if (bb != sb * 2 && bb > 0) r.sb = Math.max(1, bb / 2);
        r.maxPlayers = maxPlayers;
        r.settleTimeMins = settleTimeMins;
        r.rakePercent = rakePercent;
        r.inChip = r.bb() * 40;
        r.inMinRate = 1;
        r.inMaxRate = 10;
        return r;
    }

    // ==================== 工具 ====================

    private static void fail(String msg) {
        throw new IllegalArgumentException(msg);
    }

    private static String str(Map<String, Object> m, String k, String def) {
        Object v = m.get(k);
        return v != null ? v.toString() : def;
    }

    private static long lng(Map<String, Object> m, String k, long def) {
        Object v = m.get(k);
        if (v instanceof Number n) return n.longValue();
        if (v instanceof String s) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return def;
    }

    private static boolean flag(Map<String, Object> m, String k) {
        Object v = m.get(k);
        if (v instanceof Boolean b) return b;
        if (v instanceof Number n) return n.intValue() == 1;
        if (v instanceof String s) return "1".equals(s) || "true".equalsIgnoreCase(s);
        return false;
    }
}
