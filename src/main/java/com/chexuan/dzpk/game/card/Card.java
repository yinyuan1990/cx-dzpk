package com.chexuan.dzpk.game.card;

import lombok.EqualsAndHashCode;
import lombok.Getter;

/**
 * 扑克牌 — 字段编码与老德州 Pocer 完全一致:
 *   suit(老 type): 方块1 草花2 红桃3 黑桃4
 *   rank(老 size2): 2~14,14=A
 *   id(老 size1): 0~12方块 13~25草花 26~38红桃 39~51黑桃,id=(suit-1)*13+(rank-2)
 */
@Getter
@EqualsAndHashCode(of = "id")
public class Card {

    private final int suit;
    private final int rank;
    private final int id;

    public Card(int suit, int rank) {
        if (suit < 1 || suit > 4 || rank < 2 || rank > 14) {
            throw new IllegalArgumentException("bad card suit=" + suit + " rank=" + rank);
        }
        this.suit = suit;
        this.rank = rank;
        this.id = (suit - 1) * 13 + (rank - 2);
    }

    public static Card ofId(int id) {
        return new Card(id / 13 + 1, id % 13 + 2);
    }

    /** 便捷构造: "AS"=黑桃A "TD"=方块10, 花色 D方块/C草花/H红桃/S黑桃 (测试用) */
    public static Card of(String code) {
        int rank = switch (code.charAt(0)) {
            case 'A' -> 14; case 'K' -> 13; case 'Q' -> 12; case 'J' -> 11; case 'T' -> 10;
            default -> code.charAt(0) - '0';
        };
        int suit = switch (code.charAt(1)) {
            case 'D' -> 1; case 'C' -> 2; case 'H' -> 3; case 'S' -> 4;
            default -> throw new IllegalArgumentException(code);
        };
        return new Card(suit, rank);
    }

    @Override
    public String toString() {
        String r = switch (rank) {
            case 14 -> "A"; case 13 -> "K"; case 12 -> "Q"; case 11 -> "J"; case 10 -> "T";
            default -> String.valueOf(rank);
        };
        String s = switch (suit) {
            case 1 -> "D"; case 2 -> "C"; case 3 -> "H"; default -> "S";
        };
        return r + s;
    }
}
