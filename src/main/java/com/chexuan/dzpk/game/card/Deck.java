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
}
