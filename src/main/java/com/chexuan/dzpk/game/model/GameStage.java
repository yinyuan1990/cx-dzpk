package com.chexuan.dzpk.game.model;

/**
 * 牌局状态机 — 循环局:一手打完回 FINISHED,自动开下一手;人不够回 WAITING 等人。
 * 没有固定局时长(老德州的 30 分钟散桌已废弃,对齐扯旋循环玩法)。
 */
public enum GameStage {
    /** 等人(可坐下/带入,凑够 2 人自动开局) */
    WAITING,
    /** 翻牌前下注轮 */
    PREFLOP,
    /** 翻牌圈 */
    FLOP,
    /** 转牌圈 */
    TURN,
    /** 河牌圈 */
    RIVER,
    /** 摊牌 */
    SHOWDOWN,
    /** 结算展示 */
    SETTLING,
    /** 一手结束,间隔后开下一手 */
    FINISHED
}
