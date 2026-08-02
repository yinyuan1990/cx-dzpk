package com.chexuan.dzpk.game.rules;

import com.chexuan.dzpk.game.model.DzPlayer;
import com.chexuan.dzpk.game.model.DzRoom;

import java.util.List;

/**
 * 前注(ante,老德州 qzhu) — 每手开始、发盲注前,每个参与者强制投入固定额,
 * 直接进池(不计入 betThisRound,不影响跟注额),筹码不够全下。
 */
public final class AnteRule {

    private AnteRule() {
    }

    /** 收前注,返回进池总额(0=未开) */
    public static long post(DzRoom room, List<DzPlayer> ready) {
        long ante = room.getRules() != null ? room.getRules().getAnte() : 0;
        if (ante <= 0) return 0;
        long total = 0;
        for (DzPlayer p : ready) {
            long amt = Math.min(ante, p.getStack());
            if (amt <= 0) continue;
            p.setStack(p.getStack() - amt);
            p.setTotalBetThisHand(p.getTotalBetThisHand() + amt);
            if (p.getStack() == 0) p.setAllIn(true);
            total += amt;
        }
        room.setCollectedPot(room.getCollectedPot() + total);
        return total;
    }
}
