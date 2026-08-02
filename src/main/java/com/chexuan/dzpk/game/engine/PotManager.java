package com.chexuan.dzpk.game.engine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 边池计算 — 按每个玩家本手总投入分层切池(标准德州规则)。
 *
 * 弃牌玩家的筹码留在池里但没有资格争夺;
 * 全下额度不足的玩家只参与其投入覆盖到的层级。
 */
public final class PotManager {

    private PotManager() {
    }

    /**
     * 玩家本手投入
     *
     * @param userId 玩家
     * @param total  本手总投入(所有下注轮累计,含盲注/前注)
     * @param folded 是否已弃牌
     */
    public record Contribution(long userId, long total, boolean folded) {
    }

    /**
     * 按投入分层构建主池+边池。返回顺序:主池在前,边池按层级递增。
     */
    public static List<Pot> buildPots(List<Contribution> contributions) {
        List<Pot> pots = new ArrayList<>();

        // 所有 >0 的投入层级(去重升序):每一层切一个池
        List<Long> levels = contributions.stream()
                .map(Contribution::total)
                .filter(t -> t > 0)
                .distinct()
                .sorted(Comparator.naturalOrder())
                .toList();

        long prevLevel = 0;
        for (long level : levels) {
            Pot pot = new Pot();
            for (Contribution c : contributions) {
                long inThisLayer = Math.min(c.total(), level) - Math.min(c.total(), prevLevel);
                if (inThisLayer > 0) {
                    pot.add(inThisLayer);
                }
                // 资格:未弃牌且投入达到本层级
                if (!c.folded() && c.total() >= level) {
                    pot.getEligibleUserIds().add(c.userId());
                }
            }
            prevLevel = level;
            if (pot.getAmount() > 0) {
                // 与上一个池资格相同则合并(避免碎池:弃牌者制造的层级)
                if (!pots.isEmpty()
                        && pots.get(pots.size() - 1).getEligibleUserIds().equals(pot.getEligibleUserIds())) {
                    pots.get(pots.size() - 1).add(pot.getAmount());
                } else {
                    pots.add(pot);
                }
            }
        }
        return pots;
    }
}
