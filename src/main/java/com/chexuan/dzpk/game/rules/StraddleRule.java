package com.chexuan.dzpk.game.rules;

import com.chexuan.dzpk.game.model.DzPlayer;
import com.chexuan.dzpk.game.model.DzRoom;

/**
 * 抓头(straddle,老德州 sdlon) — 开启时 ≥3 人局,BB 下家强制多下一个 2BB 盲注:
 *   翻前跟注额变 2BB,最小加注增量 2BB,行动从抓头位下家开始;
 *   抓头者同盲注待遇(未 acted,翻前有最后行动权)。
 * 两人局(HU)不抓头。
 */
public final class StraddleRule {

    private StraddleRule() {
    }

    /**
     * 发完大小盲后调用。抓头成功返回抓头座位号,未开启/不满足返回 -1。
     * 调用方约定:pay 已把盲注扣好,本方法内自行扣抓头注并更新 currentBet/minRaise。
     */
    public static int post(DzRoom room, java.util.function.BiFunction<DzPlayer, Long, Long> pay) {
        RoomRules rules = room.getRules();
        if (rules == null || !rules.isStraddleOn()) return -1;
        if (room.readyPlayers().size() < 3) return -1;

        int seat = room.nextSeat(room.getBbSeat(), DzPlayer::isInHand);
        if (seat == -1 || seat == room.getSbSeat() || seat == room.getBbSeat()) return -1;
        DzPlayer p = room.playerAtSeat(seat);
        if (p == null || p.getStack() <= 0) return -1;

        long straddle = room.getBb() * 2;
        pay.apply(p, straddle);  // 不够则全下(pay 内部处理)
        if (p.getBetThisRound() >= straddle) {
            // 足额抓头:跟注线 2BB,最小加注增量 2BB(最小加注到 4BB)
            room.setCurrentBet(straddle);
            room.setMinRaise(straddle);
        }
        return seat;
    }
}
