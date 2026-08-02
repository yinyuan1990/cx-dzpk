package com.chexuan.dzpk.game.card;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BiPai(移植自老德州)牌型评估与比较测试
 */
class BiPaiTest {

    private static HandResult eval(String... codes) {
        Card[] cards = new Card[7];
        for (int i = 0; i < 7; i++) {
            cards[i] = Card.of(codes[i]);
        }
        return BiPai.evaluate(cards);
    }

    @Test
    void royalFlush() {
        HandResult r = eval("AS", "KS", "QS", "JS", "TS", "2D", "3C");
        assertEquals(1, r.getType());
    }

    @Test
    void straightFlush() {
        HandResult r = eval("9H", "8H", "7H", "6H", "5H", "AD", "AC");
        assertEquals(2, r.getType());
        assertEquals(9, r.getBest5()[4].getRank());
    }

    @Test
    void wheelStraightFlush() {
        // A-2-3-4-5 同花顺,顶张是 5
        HandResult r = eval("AD", "2D", "3D", "4D", "5D", "KS", "QH");
        assertEquals(2, r.getType());
        assertEquals(5, r.getBest5()[4].getRank());
    }

    @Test
    void fourOfAKind() {
        HandResult r = eval("9S", "9H", "9D", "9C", "AS", "2D", "3C");
        assertEquals(3, r.getType());
        assertEquals(9, r.getBest5()[0].getRank());
        assertEquals(14, r.getBest5()[4].getRank()); // 踢脚 A
    }

    @Test
    void fourOfAKindTopQuads() {
        // 四条是最大的四张(AAAA),踢脚应取剩余最大 K
        HandResult r = eval("AS", "AH", "AD", "AC", "KS", "2D", "3C");
        assertEquals(3, r.getType());
        assertEquals(14, r.getBest5()[0].getRank());
        assertEquals(13, r.getBest5()[4].getRank());
    }

    @Test
    void fullHouse() {
        HandResult r = eval("KS", "KH", "KD", "5C", "5S", "2D", "3C");
        assertEquals(4, r.getType());
        assertEquals(13, r.getBest5()[0].getRank());
        assertEquals(5, r.getBest5()[4].getRank());
    }

    @Test
    void fullHouseTwoTrips() {
        // 两个三条 → 大的做三条,小的做对子
        HandResult r = eval("KS", "KH", "KD", "5C", "5S", "5D", "3C");
        assertEquals(4, r.getType());
        assertEquals(13, r.getBest5()[0].getRank());
        assertEquals(5, r.getBest5()[4].getRank());
    }

    @Test
    void flush() {
        HandResult r = eval("AH", "JH", "9H", "6H", "2H", "KS", "QD");
        assertEquals(5, r.getType());
        assertEquals(14, r.getBest5()[4].getRank());
    }

    @Test
    void straight() {
        HandResult r = eval("9S", "8H", "7D", "6C", "5S", "AD", "AC");
        assertEquals(6, r.getType());
        assertEquals(9, r.getBest5()[4].getRank());
    }

    @Test
    void wheelStraight() {
        HandResult r = eval("AS", "2H", "3D", "4C", "5S", "9D", "JC");
        assertEquals(6, r.getType());
    }

    @Test
    void threeOfAKind() {
        HandResult r = eval("8S", "8H", "8D", "AC", "KS", "2D", "3C");
        assertEquals(7, r.getType());
        assertEquals(8, r.getBest5()[1].getRank());
    }

    @Test
    void twoPair() {
        HandResult r = eval("QS", "QH", "7D", "7C", "AS", "2D", "3C");
        assertEquals(8, r.getType());
        assertEquals(12, r.getBest5()[2].getRank()); // 大对在 [2]
        assertEquals(14, r.getBest5()[4].getRank()); // 踢脚 A
    }

    @Test
    void threePairsPickTopTwo() {
        // 三对 → 取大的两对 QQ 99,踢脚取剩余最大(A)
        HandResult r = eval("QS", "QH", "9D", "9C", "5S", "5D", "AC");
        assertEquals(8, r.getType());
        assertEquals(12, r.getBest5()[2].getRank());
        assertEquals(9, r.getBest5()[0].getRank());
        assertEquals(14, r.getBest5()[4].getRank());
    }

    @Test
    void onePair() {
        HandResult r = eval("TS", "TH", "AD", "KC", "QS", "3D", "2C");
        assertEquals(9, r.getType());
        assertEquals(10, r.getBest5()[0].getRank());
        assertEquals(14, r.getBest5()[4].getRank());
    }

    @Test
    void highCard() {
        HandResult r = eval("AS", "KH", "QD", "9C", "7S", "5D", "3C");
        assertEquals(10, r.getType());
        assertEquals(14, r.getBest5()[4].getRank());
    }

    // ==================== 比较 ====================

    @Test
    void compareDifferentTypes() {
        HandResult flush = eval("AH", "JH", "9H", "6H", "2H", "KS", "QD");
        HandResult straight = eval("9S", "8H", "7D", "6C", "5S", "AD", "KC");
        assertEquals(0, BiPai.compare(flush, straight)); // 同花 > 顺子
        assertEquals(1, BiPai.compare(straight, flush));
    }

    @Test
    void compareKicker() {
        // 同样一对 T,踢脚 A > K
        HandResult a = eval("TS", "TH", "AD", "QC", "9S", "3D", "2C");
        HandResult b = eval("TC", "TD", "KD", "QS", "9H", "3C", "2D");
        assertEquals(0, BiPai.compare(a, b));
        assertEquals(1, BiPai.compare(b, a));
    }

    @Test
    void compareTie() {
        // 公共牌打平:双方都用公共顺子
        HandResult a = eval("2S", "3H", "9D", "8C", "7S", "6D", "5C");
        HandResult b = eval("2H", "3D", "9D", "8C", "7S", "6D", "5C");
        assertEquals(-1, BiPai.compare(a, b));
    }

    @Test
    void compareWheelVsHigherStraight() {
        HandResult wheel = eval("AS", "2H", "3D", "4C", "5S", "9D", "JC");
        HandResult six = eval("2S", "3H", "4D", "5C", "6S", "9H", "JD");
        assertEquals(1, BiPai.compare(wheel, six)); // 6 高顺 > 轮子
    }

    @Test
    void compareTwoPairLayout() {
        // 两对:先比大对,再比小对,再比踢脚
        HandResult a = eval("QS", "QH", "7D", "7C", "AS", "2D", "3C");
        HandResult b = eval("QC", "QD", "8D", "8C", "KS", "2H", "3D");
        assertEquals(1, BiPai.compare(a, b)); // QQ88 > QQ77
    }

    @Test
    void randomSevenNeverCrashes() {
        // 冒烟:随机 5000 组 7 张牌,评估+自比不崩且自比为平
        java.util.Random rnd = new java.util.Random(42);
        for (int n = 0; n < 5000; n++) {
            java.util.List<Integer> ids = new java.util.ArrayList<>();
            for (int i = 0; i < 52; i++) ids.add(i);
            java.util.Collections.shuffle(ids, rnd);
            Card[] cards = new Card[7];
            for (int i = 0; i < 7; i++) cards[i] = Card.ofId(ids.get(i));
            HandResult r = BiPai.evaluate(cards);
            assertNotNull(r);
            assertTrue(r.getType() >= 1 && r.getType() <= 10);
            for (Card c : r.getBest5()) assertNotNull(c);
            assertEquals(-1, BiPai.compare(r, r));
        }
    }
}
