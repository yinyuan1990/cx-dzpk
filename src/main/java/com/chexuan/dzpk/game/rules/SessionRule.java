package com.chexuan.dzpk.game.rules;

import com.chexuan.dzpk.game.model.DzPlayer;
import com.chexuan.dzpk.game.model.DzRoom;

/**
 * 上桌时段规则:
 *   gameMinTime(老 gmxt) — 最短上桌时间,未满不能站起/离桌;
 *   aheadLeaveOn(老 ahdlon) — 允许提前离桌(开=无视最短时间);
 *   autoStartNum(老 autopu) — 坐满 N 人才自动开局。
 */
public final class SessionRule {

    private SessionRule() {
    }

    /** 站起/离桌校验:可以走返回 null,不可以返回提示语 */
    public static String checkStandUp(DzRoom room, DzPlayer p) {
        RoomRules rules = room.getRules();
        if (rules == null || rules.isAheadLeaveOn() || rules.getGameMinTime() <= 0) return null;
        if (p.getBringInThisPeriod() <= 0 && p.getHandCount() == 0) return null; // 没玩过随时可走
        long needMs = rules.getGameMinTime() * 60_000L;
        long played = p.effectiveMs();
        if (played < needMs) {
            long leftMin = (needMs - played + 59_999) / 60_000;
            return "上桌未满 " + rules.getGameMinTime() + " 分钟,还需 " + leftMin + " 分钟才能站起";
        }
        return null;
    }
}
