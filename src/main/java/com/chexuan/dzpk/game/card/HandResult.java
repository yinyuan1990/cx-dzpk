package com.chexuan.dzpk.game.card;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 7 张牌评估结果 — 对应老德州 BiPai.zuidapai 返回的 Object[]:
 *   best5(老 obj[0]/zuidaPocers): 最大 5 张,位置布局与老代码一致(比较逻辑依赖位置)
 *   type(老 obj[1]/pocerType): 1皇家同花顺 2同花顺 3四条 4葫芦 5同花 6顺子 7三条 8两对 9一对 10高牌
 *                              数值越小牌型越大
 */
@Getter
@AllArgsConstructor
public class HandResult {

    public static final String[] TYPE_NAMES = {
            "", "皇家同花顺", "同花顺", "四条", "葫芦", "同花", "顺子", "三条", "两对", "一对", "高牌"
    };

    private final Card[] best5;
    private final int type;

    public String typeName() {
        return TYPE_NAMES[type];
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(typeName()).append(" [");
        for (int i = 0; i < best5.length; i++) {
            if (i > 0) sb.append(' ');
            sb.append(best5[i]);
        }
        return sb.append(']').toString();
    }
}
