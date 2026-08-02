package com.chexuan.dzpk.game.rules;

import com.chexuan.dzpk.game.engine.Pot;
import com.chexuan.dzpk.game.model.DzPlayer;
import com.chexuan.dzpk.game.model.DzRoom;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 埋牌(muck,老德州 muckon) — 开启时摊牌只亮"拿到钱的人"的牌,
 * 输家自动埋牌不展示;关闭时所有未弃牌者都亮。
 */
public final class MuckRule {

    private MuckRule() {
    }

    /** 摊牌时该玩家是否亮牌 */
    public static boolean shouldShow(DzRoom room, DzPlayer p, List<Pot> pots) {
        if (!p.contesting()) return false;
        RoomRules rules = room.getRules();
        if (rules == null || !rules.isMuckOn()) return true;
        // muck 开:只亮赢家(在任意池分到钱 → netWin 累加前已加 stack,这里看分池结果)
        Set<Long> winners = new HashSet<>();
        for (Pot pot : pots) {
            if (pot.getWinnerUserIds() != null) winners.addAll(pot.getWinnerUserIds());
        }
        return winners.contains(p.getUserId());
    }
}
