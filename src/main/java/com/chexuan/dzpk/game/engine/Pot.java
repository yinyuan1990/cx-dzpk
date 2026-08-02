package com.chexuan.dzpk.game.engine;

import lombok.Getter;

import java.util.ArrayList;
import java.util.List;

/**
 * 一个奖池(主池或边池)
 */
@Getter
public class Pot {

    /** 池内金额 */
    private long amount;

    /** 有资格争夺此池的玩家(未弃牌且下注达到此池层级) */
    private final List<Long> eligibleUserIds = new ArrayList<>();

    /** 结算后填:分到此池的赢家(MuckRule 判断亮牌用) */
    private final List<Long> winnerUserIds = new ArrayList<>();

    public void add(long v) {
        this.amount += v;
    }
}
