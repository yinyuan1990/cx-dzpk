package com.chexuan.dzpk.robot;

import com.chexuan.dzpk.game.model.DzPlayer;
import com.chexuan.dzpk.game.model.DzRoom;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongPredicate;

/**
 * 盈利控盘(对齐扯旋 ProfitControlService 的决策层版本):
 *   目标:机器人对真人的净收益收敛到目标(absolute 绝对值 / rate=真人流水×比率;负=放水)。
 *   杠杆:只走【决策控盘】——机器人有上帝视角(RobotBrain.isGodWin),
 *         吃分手:赢牌机器人做大池、输牌机器人不喂;
 *         放水手:赢牌机器人弃牌让出、输牌机器人限额喂池(单手预算封顶)。
 *   闭环:每手 SETTLE 记账 robotNet = -(真人本手净),gap=目标-账本 决定下手方向与预算。
 *   粒度:房间级(参数走 RobotParamService 两层:俱乐部默认+房间覆盖)。
 *   注:不做发牌干预(扯旋另有 overrideDeal),上帝视角决策已足够收敛。
 */
@Slf4j
@Service
public class DzProfitControl {

    public static class RoomState {
        public long ledgerNet;     // 机器人对真人累计净收益(负=已放水)
        public long volume;        // 累计真人下注量(rate 模式分母)
        public int handCount;      // 已记账手数
        public int bias;           // 本手方向:+1 吃分 / -1 放水 / 0 中性
        public long budgetLeft;    // 本手剩余推动预算(放水=还能喂多少)
        public long lastGap;       // 展示用
    }

    private final RobotParamService params;
    private final Map<Long, RoomState> states = new ConcurrentHashMap<>();

    public DzProfitControl(RobotParamService params) {
        this.params = params;
    }

    private RoomState state(long roomId) {
        return states.computeIfAbsent(roomId, k -> new RoomState());
    }

    // ==================== 每手计划(HAND_START 时调) ====================

    /** 开手定方向:enabled 且桌上真人机器人同桌才控;否则中性 */
    public void planHand(DzRoom room, LongPredicate isRobot) {
        RoomState st = state(room.getRoomId());
        st.bias = 0;
        st.budgetLeft = 0;
        if (!params.getBool(room, "profit_enabled")) return;
        boolean hasRobot = false, hasHuman = false;
        for (DzPlayer p : room.getSeats()) {
            if (p == null || !p.isInHand()) continue;
            if (isRobot.test(p.getUserId())) hasRobot = true;
            else hasHuman = true;
        }
        if (!hasRobot || !hasHuman) return;

        long target = targetNet(room, st);
        long gap = target - st.ledgerNet;
        long band = Math.max(room.getBb() * 10L, Math.abs(target) / 10);
        st.lastGap = gap;
        if (Math.abs(gap) <= band) return; // 中性:交回自然牌力

        long cap = params.getLong(room, "profit_per_hand_cap");
        if (cap <= 0) cap = room.getBb() * 200L;
        long strength = Math.max(1, Math.min(100, params.getLong(room, "profit_adjust_strength")));
        long budget = Math.min(cap, Math.abs(gap) * strength / 100);
        st.bias = gap > 0 ? 1 : -1;
        st.budgetLeft = Math.max(room.getBb() * 2L, budget);
        log.debug("控盘计划: roomId={}, gap={}, bias={}, budget={}", room.getRoomId(), gap, st.bias, st.budgetLeft);
    }

    private long targetNet(DzRoom room, RoomState st) {
        if ("rate".equalsIgnoreCase(params.profitMode(room.getClubId()))) {
            long rate = params.getLong(room, "profit_target_rate"); // 百分比,可负
            return st.volume * rate / 100;
        }
        return params.getLong(room, "profit_target");
    }

    // ==================== 决策接口(RobotBrain 用) ====================

    /** 本手方向:+1 吃分 / -1 放水 / 0 中性 */
    public int biasFor(long roomId) {
        RoomState st = states.get(roomId);
        return st == null ? 0 : st.bias;
    }

    /** 放水喂池剩余预算 */
    public long budgetLeft(long roomId) {
        RoomState st = states.get(roomId);
        return st == null ? 0 : st.budgetLeft;
    }

    /** 机器人喂池消耗预算(放水手每次 call/raise 的新增投入) */
    public void consume(long roomId, long amount) {
        RoomState st = states.get(roomId);
        if (st != null && amount > 0) st.budgetLeft = Math.max(0, st.budgetLeft - amount);
    }

    // ==================== 记账(SETTLE 时调) ====================

    /** 一手结算:账本 = -(真人本手净);真人流水累计。无真人不记账 */
    public void onSettle(DzRoom room, LongPredicate isRobot) {
        RoomState st = states.get(room.getRoomId());
        if (st == null) return;
        long humanNet = 0, humanBet = 0;
        boolean hasHuman = false, hasRobot = false;
        for (DzPlayer p : room.getSeats()) {
            if (p == null || !p.isInHand()) continue;
            if (isRobot.test(p.getUserId())) {
                hasRobot = true;
            } else {
                hasHuman = true;
                humanNet += p.getNetWin();
                humanBet += p.getTotalBetThisHand();
            }
        }
        st.bias = 0;
        st.budgetLeft = 0;
        if (!hasHuman || !hasRobot) return;
        st.ledgerNet += -humanNet;
        st.volume += humanBet;
        st.handCount++;
    }

    // ==================== 管理台状态 / 清理 ====================

    public Map<String, Object> status(DzRoom room) {
        RoomState st = states.get(room.getRoomId());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", params.getBool(room, "profit_enabled"));
        out.put("mode", params.profitMode(room.getClubId()));
        out.put("target", targetNet(room, st == null ? new RoomState() : st));
        out.put("ledgerNet", st == null ? 0 : st.ledgerNet);
        out.put("volume", st == null ? 0 : st.volume);
        out.put("handCount", st == null ? 0 : st.handCount);
        out.put("bias", st == null ? 0 : st.bias);
        out.put("lastGap", st == null ? 0 : st.lastGap);
        return out;
    }

    /** 重置账本(管理台改目标后从零收敛) */
    public void resetLedger(long roomId) {
        states.remove(roomId);
    }

    public void clearRoom(long roomId) {
        states.remove(roomId);
    }
}
