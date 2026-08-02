package com.chexuan.dzpk.game.model;

import com.chexuan.dzpk.game.card.Card;
import com.chexuan.dzpk.game.card.HandResult;
import lombok.Getter;
import lombok.Setter;

/**
 * 座位上的玩家(一个房间内的状态)
 */
@Getter
@Setter
public class DzPlayer {

    private long userId;
    private String nickname;
    private int seat;

    /** 桌上筹码 */
    private long stack;

    /** 坐下时的客户端 IP(AccessRule 同 IP 限制用,机器人为 null) */
    private String ip;

    // ==================== 入池率统计(vpOn) ====================

    /** 本周期主动入池手数(翻前主动跟注/加注) */
    private int vpipCount;
    /** 本手是否已计过入池(防重复) */
    private boolean vpipThisHand;

    // ==================== 本手牌状态 ====================

    /** 本手是否参与(开局时在座且有筹码) */
    private boolean inHand;
    /** 已弃牌 */
    private boolean folded;
    /** 已全下 */
    private boolean allIn;
    /** 本轮已行动过(下注轮完成判断用) */
    private boolean acted;
    /** 本下注轮已投入 */
    private long betThisRound;
    /** 本手总投入(边池计算用) */
    private long totalBetThisHand;
    /** 手牌 */
    private Card[] holeCards;
    /** 摊牌结果(结算时填) */
    private HandResult handResult;
    /** 本手净输赢(结算广播用) */
    private long netWin;

    // ==================== 局间意愿 ====================

    /** 局间生效:站起 */
    private boolean pendingStandUp;
    /** 局间生效:追加带入 */
    private long pendingBuyin;
    /** 暂离/放假中(不参与发牌,对齐扯旋 ON_LEAVE) */
    private boolean sittingOut;
    /** 断线(超时自动弃牌沿用,不踢人) */
    private boolean offline;

    // ==================== 留座暂离/放假(对齐扯旋 seatReserve) ====================

    /** 放假到期时间戳(0=未在放假) */
    private long seatReserveDeadline;
    /** 本结算周期是否已用过一次暂离(周期结算清零) */
    private boolean seatReserveUsed;
    /** 牌局中未弃牌申请暂离 → 弃牌/局末再进放假 */
    private boolean manualLeavePending;
    /** 断线待处理:代弃后自动进放假(对齐扯旋 vacationPending) */
    private boolean vacationPending;
    /** 放假超时任务 */
    private transient java.util.concurrent.ScheduledFuture<?> graceTimer;

    // ==================== 离座时间累计(逃跑罚金用) ====================

    /** 本次断线开始时刻(0=在线) */
    private long offlineSince;
    /** 本周期累计离线时长(ms) */
    private long leaveAccumMs;

    // ==================== 周期结算(循环玩法,对齐扯旋 v46/v52.9r) ====================

    /** 结算周期计时:累计游戏时间(ms),两点法落账 */
    private long gameTimeAccumMs;
    /** 计时闸门开启时刻(0=未开闸);发牌参与时开,站起/周期结算时关 */
    private long gameTimerOpenAt;
    /** 本周期累计带入 */
    private long bringInThisPeriod;
    /** 本周期手数/胜/负/平 */
    private int handCount;
    private int winCount;
    private int loseCount;
    /** 周期结算后等待补带入(带入则开新周期,超时自动站起) */
    private boolean awaitingBuyin;
    /** 补带入等待截止时间戳 */
    private long awaitBuyinDeadline;
    /** 结算周期序号(每次周期结算+1,战绩分段用) */
    private int settlePeriodSeq;

    /** 周期计时:当前有效累计(ms) */
    public long effectiveMs() {
        if (gameTimerOpenAt <= 0) return gameTimeAccumMs;
        return gameTimeAccumMs + Math.max(0, System.currentTimeMillis() - gameTimerOpenAt);
    }

    /** 开闸(已开着则不动) */
    public void openGate() {
        if (gameTimerOpenAt <= 0) {
            gameTimerOpenAt = System.currentTimeMillis();
        }
    }

    /** 关闸落账 */
    public void closeGate() {
        if (gameTimerOpenAt > 0) {
            gameTimeAccumMs += Math.max(0, System.currentTimeMillis() - gameTimerOpenAt);
            gameTimerOpenAt = 0;
        }
    }

    /** 周期结算后重置(新周期) */
    public void resetPeriod() {
        gameTimeAccumMs = 0;
        gameTimerOpenAt = 0;
        bringInThisPeriod = 0;
        handCount = 0;
        winCount = 0;
        loseCount = 0;
        vpipCount = 0;
        seatReserveUsed = false;  // 对齐扯旋:周期结算后暂离次数重置
        leaveAccumMs = 0;
        settlePeriodSeq++;
    }

    /** 是否放假中(在座但暂离) */
    public boolean inGrace() {
        return sittingOut && seatReserveDeadline > 0;
    }

    /** 开始新一手前重置 */
    public void resetForHand() {
        inHand = false;
        folded = false;
        allIn = false;
        acted = false;
        betThisRound = 0;
        totalBetThisHand = 0;
        holeCards = null;
        handResult = null;
        netWin = 0;
        vpipThisHand = false;
    }

    /** 进入下一条街前重置本轮状态 */
    public void resetForStreet() {
        betThisRound = 0;
        acted = false;
    }

    /** 还能行动(在手中,没弃没全下) */
    public boolean canAct() {
        return inHand && !folded && !allIn;
    }

    /** 还在争夺奖池(没弃牌) */
    public boolean contesting() {
        return inHand && !folded;
    }
}
