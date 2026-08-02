package com.chexuan.dzpk.game.card;

/**
 * 牌型评估与比较 — 原样移植自老德州 com.i366.room.BiPai(2012, shenxing.ruan)。
 *
 * zuidapai: 7 张牌取最大 5 张,算法逐行对应老代码(变量名保留以便对照),
 *           仅把 Pocer 换成 Card(getType->getSuit, getSize2->getRank)。
 * compare : 移植自老 bipai2 的同牌型逐位比较规则(去掉了 RoomPersion 的
 *           在线状态/弃牌分支——那些由牌局引擎在摊牌前处理)。
 */
public final class BiPai {

    private BiPai() {
    }

    /**
     * 7 张牌评估(2 手牌 + 5 公共牌)。不修改调用方数组。
     */
    public static HandResult evaluate(Card[] seven) {
        if (seven == null || seven.length != 7) {
            throw new IllegalArgumentException("need 7 cards");
        }
        Card[] pocer = seven.clone();
        return zuidapai(pocer);
    }

    /**
     * 比较两手牌。返回值沿用老 bipai2 约定:
     *   -1 一样大   0: a 大   1: b 大
     */
    public static int compare(HandResult a, HandResult b) {
        if (a.getType() == b.getType()) {
            Card[] r1 = a.getBest5();
            Card[] r2 = b.getBest5();
            switch (a.getType()) {
                case 1: // 皇家同花顺
                    return -1;
                case 2: // 同花顺
                    return cmpPos(r1, r2, 4);
                case 3: // 四条
                    return cmpChain(r1, r2, 0, 4);
                case 4: // 葫芦
                    return cmpChain(r1, r2, 0, 4);
                case 5: // 同花
                    return cmpChain(r1, r2, 4, 3, 2, 1, 0);
                case 6: // 顺子
                    return cmpPos(r1, r2, 4);
                case 7: // 三条
                    return cmpChain(r1, r2, 1, 4, 3);
                case 8: // 两对
                    return cmpChain(r1, r2, 2, 0, 4);
                case 9: // 一对
                    return cmpChain(r1, r2, 0, 4, 3, 2);
                case 10: // 高牌
                    return cmpChain(r1, r2, 4, 3, 2, 1, 0);
                default:
                    return -1;
            }
        } else if (a.getType() < b.getType()) {
            return 0;
        } else {
            return 1;
        }
    }

    private static int cmpPos(Card[] r1, Card[] r2, int idx) {
        if (r1[idx].getRank() > r2[idx].getRank()) return 0;
        if (r2[idx].getRank() > r1[idx].getRank()) return 1;
        return -1;
    }

    private static int cmpChain(Card[] r1, Card[] r2, int... idxs) {
        for (int idx : idxs) {
            int c = cmpPos(r1, r2, idx);
            if (c != -1) return c;
        }
        return -1;
    }

    // ================================================================
    // 以下为老 BiPai.zuidapai 的逐行移植,保留原变量名/流程/注释
    // ================================================================

    @SuppressWarnings("ManualArrayCopy")
    private static HandResult zuidapai(Card[] pocer) {
        Card[] p6 = new Card[5];
        // 1 对牌大小进行排序 由小到大
        for (int i = 0; i < pocer.length; i++) {
            for (int j = 0; j < pocer.length - i - 1; j++) {
                if (pocer[j].getRank() > pocer[j + 1].getRank()) { // 大的往上冒
                    swap(pocer, j, j + 1);
                }
            }
        }
        int[] ss = {-1, -1, -1, -1};
        Card[] p1 = new Card[7];
        Card[] p2 = new Card[7];
        Card[] p3 = new Card[7];
        Card[] p4 = new Card[7];
        // 分颜色
        for (int i = 0; i < pocer.length; i++) {
            if (pocer[i].getSuit() == 1) {
                ss[0] = ss[0] + 1;
                p1[ss[0]] = pocer[i];
            } else if (pocer[i].getSuit() == 2) {
                ss[1] = ss[1] + 1;
                p2[ss[1]] = pocer[i];
            } else if (pocer[i].getSuit() == 3) {
                ss[2] = ss[2] + 1;
                p3[ss[2]] = pocer[i];
            } else if (pocer[i].getSuit() == 4) {
                ss[3] = ss[3] + 1;
                p4[ss[3]] = pocer[i];
            }
        }
        int bj = -1;
        if (ss[0] > 3) bj = 0;
        if (ss[1] > 3) bj = 1;
        if (ss[2] > 3) bj = 2;
        if (ss[3] > 3) bj = 3;
        // 是个同花
        if (bj != -1) {
            Card[] p5 = null;
            if (bj == 0) p5 = p1;
            else if (bj == 1) p5 = p2;
            else if (bj == 2) p5 = p3;
            else p5 = p4;

            // 看看是不是顺子 如果是顺子就是同花顺 A-5 的顺子也要考虑
            int bj2 = 0;
            int bj3 = -1;
            int insize = ss[bj];
            for (int k = 0; k < insize; k++) {
                int p_ = p5[insize - k].getRank() - p5[insize - k - 1].getRank();
                if (p_ == 1 || (p_ == 9 && p5[insize - k].getRank() == 14)) {
                    p6[4 - bj2] = p5[insize - k];
                    p6[3 - bj2] = p5[insize - k - 1];
                    if (bj2 == 3) {
                        bj3 = k;
                        break;
                    }
                    bj2++;
                } else {
                    bj2 = 0;
                }
            }

            // 同花顺
            if (bj3 != -1) {
                if (p6[4].getRank() == 14 && p6[3].getRank() != 13) {
                    // A-5 轮子:A 挪到头部,顶张为 5
                    Card[] p6_ = {null, null, null, null, null};
                    p6_[0] = p6[4];
                    p6_[1] = p6[0];
                    p6_[2] = p6[1];
                    p6_[3] = p6[2];
                    p6_[4] = p6[3];
                    p6 = p6_;
                }
                if (p6[4].getRank() == 14 && p6[3].getRank() == 13) {
                    return new HandResult(p6, 1); // 皇家同花顺
                }
                return new HandResult(p6, 2);
            }

            if (p5[ss[bj]].getRank() == 14 && p5[0].getRank() == 2 && p5[1].getRank() == 3
                    && p5[2].getRank() == 4 && p5[3].getRank() == 5) {
                p6[0] = p5[ss[bj]];
                p6[1] = p5[0];
                p6[2] = p5[1];
                p6[3] = p5[2];
                p6[4] = p5[3];
                return new HandResult(p6, 2);
            }
            // 是个同花但是不是同花顺
            p6[4] = p5[ss[bj]];
            p6[3] = p5[ss[bj] - 1];
            p6[2] = p5[ss[bj] - 2];
            p6[1] = p5[ss[bj] - 3];
            p6[0] = p5[ss[bj] - 4];
            return new HandResult(p6, 5);
        }

        Card[][] p11 = new Card[7][4]; // 扑克按相同数字分组 最多7组每组最多4张
        int[] ss3 = {-1, -1, -1, -1, -1, -1, -1};
        int dui4 = 0;
        int dui4_tm1 = -1;
        int dui3 = 0;
        int dui3_tm1 = -1; // 第一个三条
        int dui3_tm2 = -1; // 第二个三条
        int dui2 = 0;
        int dui2_tm1 = -1;
        int dui2_tm2 = -1;
        int dui2_tm3 = -1;
        for (int e = 0; e < pocer.length; e++) {
            for (int f = 0; f < 7; f++) {
                if (ss3[f] == -1) {
                    ss3[f] = 0;
                    p11[f][0] = pocer[e];
                    break;
                }
                if (p11[f][0].getRank() == pocer[e].getRank()) {
                    ss3[f] = ss3[f] + 1;
                    p11[f][ss3[f]] = pocer[e];
                    if (ss3[f] == 3) { // 4张相同
                        dui4++;
                        dui4_tm1 = f;
                    }
                    if (ss3[f] == 2) { // 3张相同
                        dui3++;
                        if (dui3 == 1) dui3_tm1 = f;
                        else dui3_tm2 = f;
                    }
                    if (ss3[f] == 1) { // 2张相同
                        dui2++;
                        if (dui2 == 1) dui2_tm1 = f;
                        else if (dui2 == 2) dui2_tm2 = f;
                        else dui2_tm3 = f;
                    }
                    break;
                }
            }
        }
        // 四条
        if (dui4 != 0) {
            p6[0] = p11[dui4_tm1][0];
            p6[1] = p11[dui4_tm1][1];
            p6[2] = p11[dui4_tm1][2];
            p6[3] = p11[dui4_tm1][3];
            if (p6[0].getRank() == pocer[6].getRank()) {
                p6[4] = pocer[2];
            } else {
                p6[4] = pocer[6];
            }
            return new HandResult(p6, 3);
        }
        // 葫芦
        if (dui3 > 0 && dui2 >= 2) {
            if (dui3 == 2) {
                if (p11[dui3_tm1][0].getRank() > p11[dui3_tm2][0].getRank()) {
                    p6[0] = p11[dui3_tm1][0];
                    p6[1] = p11[dui3_tm1][1];
                    p6[2] = p11[dui3_tm1][2];
                    p6[3] = p11[dui3_tm2][1];
                    p6[4] = p11[dui3_tm2][2];
                } else {
                    p6[0] = p11[dui3_tm2][0];
                    p6[1] = p11[dui3_tm2][1];
                    p6[2] = p11[dui3_tm2][2];
                    p6[3] = p11[dui3_tm1][1];
                    p6[4] = p11[dui3_tm1][2];
                }
            } else {
                p6[0] = p11[dui3_tm1][0];
                p6[1] = p11[dui3_tm1][1];
                p6[2] = p11[dui3_tm1][2];
                if (dui2 == 2) {
                    if (dui2_tm1 == dui3_tm1) {
                        p6[3] = p11[dui2_tm2][0];
                        p6[4] = p11[dui2_tm2][1];
                    } else {
                        p6[3] = p11[dui2_tm1][0];
                        p6[4] = p11[dui2_tm1][1];
                    }
                } else {
                    if (dui2_tm1 == dui3_tm1) {
                        if (p11[dui2_tm2][0].getRank() > p11[dui2_tm3][0].getRank()) {
                            p6[3] = p11[dui2_tm2][0];
                            p6[4] = p11[dui2_tm2][1];
                        } else {
                            p6[3] = p11[dui2_tm3][0];
                            p6[4] = p11[dui2_tm3][1];
                        }
                    } else if (dui2_tm2 == dui3_tm1) {
                        if (p11[dui2_tm1][0].getRank() > p11[dui2_tm3][0].getRank()) {
                            p6[3] = p11[dui2_tm1][0];
                            p6[4] = p11[dui2_tm1][1];
                        } else {
                            p6[3] = p11[dui2_tm3][0];
                            p6[4] = p11[dui2_tm3][1];
                        }
                    } else {
                        if (p11[dui2_tm1][0].getRank() > p11[dui2_tm2][0].getRank()) {
                            p6[3] = p11[dui2_tm1][0];
                            p6[4] = p11[dui2_tm1][1];
                        } else {
                            p6[3] = p11[dui2_tm2][0];
                            p6[4] = p11[dui2_tm2][1];
                        }
                    }
                }
            }
            return new HandResult(p6, 4);
        }

        // 顺子
        int bj7 = 0;
        int bj8 = -1;
        for (int i_ = 0; i_ < pocer.length - 1; i_++) {
            if (pocer[6 - i_].getRank() - pocer[5 - i_].getRank() == 1) {
                p6[4 - bj7] = pocer[6 - i_];
                p6[3 - bj7] = pocer[5 - i_];
                if (bj7 >= 3) {
                    bj8 = i_;
                    break;
                }
                bj7++;
            } else {
                if (pocer[6 - i_].getRank() - pocer[5 - i_].getRank() != 0) {
                    bj7 = 0;
                }
            }
        }
        if (bj8 != -1) {
            return new HandResult(p6, 6);
        }

        // A - 5 的顺子
        if (pocer[6].getRank() == 14 && pocer[0].getRank() == 2) {
            p6[0] = pocer[6];
            int bj9 = 1;
            for (int i_ = 0; i_ < pocer.length - 2; i_++) {
                if (pocer[i_ + 1].getRank() - pocer[i_].getRank() == 1) {
                    p6[bj9] = pocer[i_];
                    p6[bj9 + 1] = pocer[i_ + 1];
                    if (bj9 == 3) {
                        bj9 = 4;
                        break;
                    }
                    bj9++;
                } else {
                    if (pocer[i_ + 1].getRank() - pocer[i_].getRank() == 0) {
                        continue;
                    } else {
                        bj9 = 1;
                        break;
                    }
                }
            }
            if (bj9 == 4) {
                return new HandResult(p6, 6);
            }
        }

        // 三条
        if (dui3 == 1) {
            p6[0] = p11[dui3_tm1][0];
            p6[1] = p11[dui3_tm1][1];
            p6[2] = p11[dui3_tm1][2];
            if (pocer[6].getRank() != p6[0].getRank() && pocer[5].getRank() != p6[0].getRank()) {
                p6[3] = pocer[5];
                p6[4] = pocer[6];
            } else if (pocer[6].getRank() != p6[0].getRank()) {
                p6[3] = pocer[2];
                p6[4] = pocer[6];
            } else {
                p6[3] = pocer[2];
                p6[4] = pocer[3];
            }
            return new HandResult(p6, 7);
        }

        // 两对
        if (dui2 == 2) {
            p6[0] = p11[dui2_tm1][0];
            p6[1] = p11[dui2_tm1][1];
            p6[2] = p11[dui2_tm2][0];
            p6[3] = p11[dui2_tm2][1];
            for (int i = 6; i >= 0; i--) {
                if (p6[0].getRank() != pocer[i].getRank() && p6[2].getRank() != pocer[i].getRank()) {
                    p6[4] = pocer[i];
                    break;
                }
            }
            return new HandResult(p6, 8);
        }
        // 三对(取大的两对)
        if (dui2 == 3) {
            p6[0] = p11[dui2_tm2][0];
            p6[1] = p11[dui2_tm2][1];
            p6[2] = p11[dui2_tm3][0];
            p6[3] = p11[dui2_tm3][1];
            for (int i = 6; i >= 0; i--) {
                if (p6[0].getRank() != pocer[i].getRank() && p6[2].getRank() != pocer[i].getRank()) {
                    p6[4] = pocer[i];
                    break;
                }
            }
            return new HandResult(p6, 8);
        }

        // 一对
        if (dui2 == 1) {
            p6[0] = p11[dui2_tm1][0];
            p6[1] = p11[dui2_tm1][1];
            int i_ = 4;
            for (int i = 6; i >= 0; i--) {
                if (p6[0].getRank() != pocer[i].getRank()) {
                    p6[i_] = pocer[i];
                    i_--;
                    if (i_ == 1) {
                        break;
                    }
                }
            }
            return new HandResult(p6, 9);
        }
        // 高牌
        p6[0] = pocer[2];
        p6[1] = pocer[3];
        p6[2] = pocer[4];
        p6[3] = pocer[5];
        p6[4] = pocer[6];
        return new HandResult(p6, 10);
    }

    private static void swap(Object[] array, int i, int j) {
        Object temp = array[i];
        array[i] = array[j];
        array[j] = temp;
    }
}
