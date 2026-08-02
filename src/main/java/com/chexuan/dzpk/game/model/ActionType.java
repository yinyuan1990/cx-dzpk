package com.chexuan.dzpk.game.model;

/**
 * 玩家行动
 */
public enum ActionType {
    FOLD, CHECK, CALL, RAISE, ALLIN;

    public static ActionType of(String s) {
        if (s == null) return null;
        try {
            return valueOf(s.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
