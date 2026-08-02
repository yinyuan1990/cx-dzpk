package com.chexuan.dzpk.game.rules;

import com.chexuan.dzpk.game.model.DzPlayer;
import com.chexuan.dzpk.game.model.DzRoom;

/**
 * 坐下准入规则:
 *   ipLimitOn(老 ipon) — 同 IP 不允许同桌;
 *   gpsLimitOn(老 gpson) — 参数保留,当前无定位数据不生效。
 */
public final class AccessRule {

    private AccessRule() {
    }

    /** 坐下校验:可坐返回 null,否则返回提示语。ip 可为 null(机器人/测试跳过) */
    public static String checkSit(DzRoom room, String ip) {
        RoomRules rules = room.getRules();
        if (rules == null || !rules.isIpLimitOn() || ip == null || ip.isBlank()) return null;
        for (DzPlayer p : room.getSeats()) {
            if (p != null && ip.equals(p.getIp())) {
                return "本桌开启了 IP 限制,同一 IP 不能同桌";
            }
        }
        return null;
    }
}
