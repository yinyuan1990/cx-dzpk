package com.chexuan.dzpk.game.engine;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PotManagerTest {

    @Test
    void singleMainPot() {
        List<Pot> pots = PotManager.buildPots(List.of(
                new PotManager.Contribution(1, 100, false),
                new PotManager.Contribution(2, 100, false),
                new PotManager.Contribution(3, 100, false)));
        assertEquals(1, pots.size());
        assertEquals(300, pots.get(0).getAmount());
        assertEquals(List.of(1L, 2L, 3L), pots.get(0).getEligibleUserIds());
    }

    @Test
    void foldedChipsStayInPot() {
        List<Pot> pots = PotManager.buildPots(List.of(
                new PotManager.Contribution(1, 100, false),
                new PotManager.Contribution(2, 100, false),
                new PotManager.Contribution(3, 40, true))); // 弃牌 40
        assertEquals(1, pots.size());
        assertEquals(240, pots.get(0).getAmount());
        assertEquals(List.of(1L, 2L), pots.get(0).getEligibleUserIds());
    }

    @Test
    void oneAllinCreatesSidePot() {
        // p1 allin 50, p2/p3 各 200
        List<Pot> pots = PotManager.buildPots(List.of(
                new PotManager.Contribution(1, 50, false),
                new PotManager.Contribution(2, 200, false),
                new PotManager.Contribution(3, 200, false)));
        assertEquals(2, pots.size());
        assertEquals(150, pots.get(0).getAmount()); // 主池 50*3
        assertEquals(List.of(1L, 2L, 3L), pots.get(0).getEligibleUserIds());
        assertEquals(300, pots.get(1).getAmount()); // 边池 150*2
        assertEquals(List.of(2L, 3L), pots.get(1).getEligibleUserIds());
    }

    @Test
    void twoAllinsTwoSidePots() {
        List<Pot> pots = PotManager.buildPots(List.of(
                new PotManager.Contribution(1, 30, false),
                new PotManager.Contribution(2, 80, false),
                new PotManager.Contribution(3, 200, false),
                new PotManager.Contribution(4, 200, false)));
        assertEquals(3, pots.size());
        assertEquals(120, pots.get(0).getAmount()); // 30*4
        assertEquals(150, pots.get(1).getAmount()); // 50*3
        assertEquals(List.of(2L, 3L, 4L), pots.get(1).getEligibleUserIds());
        assertEquals(240, pots.get(2).getAmount()); // 120*2
        assertEquals(List.of(3L, 4L), pots.get(2).getEligibleUserIds());
    }

    @Test
    void foldedLevelDoesNotSplitPot() {
        // 弃牌者投入 60 位于两层之间,不应产生资格相同的碎池
        List<Pot> pots = PotManager.buildPots(List.of(
                new PotManager.Contribution(1, 100, false),
                new PotManager.Contribution(2, 100, false),
                new PotManager.Contribution(3, 60, true)));
        assertEquals(1, pots.size());
        assertEquals(260, pots.get(0).getAmount());
        assertEquals(List.of(1L, 2L), pots.get(0).getEligibleUserIds());
    }

    @Test
    void totalConservation() {
        List<PotManager.Contribution> cs = List.of(
                new PotManager.Contribution(1, 13, false),
                new PotManager.Contribution(2, 77, true),
                new PotManager.Contribution(3, 200, false),
                new PotManager.Contribution(4, 151, false),
                new PotManager.Contribution(5, 200, false));
        long total = cs.stream().mapToLong(PotManager.Contribution::total).sum();
        long potSum = PotManager.buildPots(cs).stream().mapToLong(Pot::getAmount).sum();
        assertEquals(total, potSum);
    }
}
