package com.chexuan.dzpk.game.rules;

import com.chexuan.dzpk.game.card.BiPai;
import com.chexuan.dzpk.game.card.Card;
import com.chexuan.dzpk.game.card.HandResult;
import com.chexuan.dzpk.game.model.DzPlayer;
import com.chexuan.dzpk.game.model.DzRoom;

import java.util.ArrayList;
import java.util.List;

/**
 * 保险(老德州 isron) — 河牌保险:
 *   两人全下、转牌已发只剩河牌时,领先方可对"反超牌(outs)"投保;
 *   保费 = 投保额 ÷ 赔率;河牌被反超 → 赔付投保额,守住 → 扣保费。
 *   保费/赔付在该手结算时直接调整筹码(平台承保,不动底池)。
 *
 * outs 与领先方判定:精确枚举 44 张未见牌,逐张 7 张评估比较。
 */
public final class InsuranceRule {

    private InsuranceRule() {
    }

    /** 赔率 ×100(outs 1~20,对齐常见德州保险赔率表) */
    private static final int[] ODDS_X100 = {
            0,    // 占位(outs=0 不可保)
            3100, 1600, 1000, 800, 600,   // 1~5
            500, 400, 350, 300, 250,      // 6~10
            220, 200, 180, 160, 140,      // 11~15
            130, 120, 110, 100, 80,       // 16~20
    };

    public static final int MAX_OUTS = 20;

    public static int oddsX100(int outs) {
        return (outs >= 1 && outs <= MAX_OUTS) ? ODDS_X100[outs] : 0;
    }

    /** 保费 = 投保额 ÷ 赔率(向上取整,平台不吃亏) */
    public static long premium(long insured, int outs) {
        int odds = oddsX100(outs);
        if (odds <= 0) return 0;
        return (insured * 100 + odds - 1) / odds;
    }

    /** 一次保险报价 */
    public static class Offer {
        public long leaderUserId;
        public long trailerUserId;
        public int outs;
        public List<String> outCards = new ArrayList<>();
        public int oddsX100;
        /** 投保上限(=底池) */
        public long maxInsure;
    }

    /**
     * 是否满足报价条件并计算 outs。
     * 条件:规则开启 / 恰好 2 人未弃牌且都无法再行动(全下跑马) / 转牌已发(board=4)。
     * 领先方 = 44 张河牌里赢面多的一方;outs = 让落后方反超的河牌数(1~20 才可保)。
     * 不满足返回 null。
     */
    public static Offer tryOffer(DzRoom room) {
        RoomRules rules = room.getRules();
        if (rules == null || !rules.isInsuranceOn()) return null;
        if (room.getBoard().size() != 4) return null;

        List<DzPlayer> contesting = room.contestingPlayers();
        if (contesting.size() != 2) return null;
        int canActCnt = 0;
        for (DzPlayer p : contesting) {
            if (p.getHoleCards() == null) return null;
            if (p.canAct()) canActCnt++;
        }
        if (canActCnt > 1) return null;   // 河牌还有下注轮,不是跑马

        DzPlayer a = contesting.get(0);
        DzPlayer b = contesting.get(1);
        boolean[] seen = new boolean[52];
        for (Card c : a.getHoleCards()) seen[c.getId()] = true;
        for (Card c : b.getHoleCards()) seen[c.getId()] = true;
        for (Card c : room.getBoard()) seen[c.getId()] = true;

        int winA = 0, winB = 0;
        List<Card> bWinCards = new ArrayList<>();
        List<Card> aWinCards = new ArrayList<>();
        for (int id = 0; id < 52; id++) {
            if (seen[id]) continue;
            Card river = Card.ofId(id);
            int cmp = BiPai.compare(eval(a, room, river), eval(b, room, river));
            if (cmp == 0) { winA++; aWinCards.add(river); }
            else if (cmp == 1) { winB++; bWinCards.add(river); }
            // -1 平分,双方都不算反超
        }
        if (winA == winB) return null; // 无明确领先方

        DzPlayer leader = winA > winB ? a : b;
        DzPlayer trailer = winA > winB ? b : a;
        List<Card> outCards = winA > winB ? bWinCards : aWinCards;
        int outs = outCards.size();
        if (outs < 1 || outs > MAX_OUTS) return null; // 0=锁定赢,>20 不承保

        Offer offer = new Offer();
        offer.leaderUserId = leader.getUserId();
        offer.trailerUserId = trailer.getUserId();
        offer.outs = outs;
        for (Card c : outCards) offer.outCards.add(c.toString());
        offer.oddsX100 = oddsX100(outs);
        offer.maxInsure = room.getCollectedPot();
        return offer;
    }

    private static HandResult eval(DzPlayer p, DzRoom room, Card river) {
        Card[] seven = new Card[7];
        seven[0] = p.getHoleCards()[0];
        seven[1] = p.getHoleCards()[1];
        for (int i = 0; i < 4; i++) seven[2 + i] = room.getBoard().get(i);
        seven[6] = river;
        return BiPai.evaluate(seven);
    }

    /** 挂在房间上的保险状态(一手一份) */
    public static class State {
        public long handNo;
        public Offer offer;
        /** 已决定(买/放弃/超时) */
        public boolean decided;
        /** 投保额(0=放弃) */
        public long insured;
        public long premium;
        public long deadline;

        public boolean pending() {
            return offer != null && !decided;
        }
    }
}
