package com.chexuan.dzpk.game.card;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一副 52 张的牌堆,SecureRandom 洗牌
 */
public class Deck {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final List<Card> cards = new ArrayList<>(52);
    private int cursor = 0;

    public Deck() {
        for (int id = 0; id < 52; id++) {
            cards.add(Card.ofId(id));
        }
        Collections.shuffle(cards, RANDOM);
    }

    public Card deal() {
        if (cursor >= cards.size()) {
            throw new IllegalStateException("deck exhausted");
        }
        return cards.get(cursor++);
    }

    public int remaining() {
        return cards.size() - cursor;
    }

    /**
     * 窥视接下来 n 张(不动游标)。机器人"上帝视角"专用——对齐老德州 AiBipai:
     * AT 用完整 5 张公共牌(含未发出的)与对手比牌判断本手最终输赢,再按性格表行动。
     */
    public List<Card> peek(int n) {
        List<Card> out = new ArrayList<>(Math.max(0, n));
        for (int i = cursor; i < Math.min(cursor + n, cards.size()); i++) {
            out.add(cards.get(i));
        }
        return out;
    }
}
