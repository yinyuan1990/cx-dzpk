package com.chexuan.dzpk.game.model;

import com.chexuan.dzpk.game.card.Card;
import com.chexuan.dzpk.game.card.Deck;
import com.chexuan.dzpk.game.engine.Pot;
import com.chexuan.dzpk.game.engine.PotManager;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

/**
 * 德州房间(内存态) — 循环局:没有固定时长,一手一结,人够就一直转。
 */
@Getter
@Setter
public class DzRoom {

    private long roomId;
    private String name;
    private long creatorUserId;

    /** 小盲 */
    private long sb;
    /** 大盲 */
    private long bb;
    /** 最大座位数(2~9) */
    private int maxPlayers;
    /** 最小/最大带入(以 bb 为单位换算好的金额) */
    private long minBuyin;
    private long maxBuyin;

    /**
     * 结算时间(分钟) — 创建房间时选(30/45/60...)。
     * 循环玩法:到点不散桌,而是"玩家周期结算":每手结束检查玩家累计游戏时间,
     * 到期的玩家抽水→退筹→清桌→等补带入,房间继续循环开局(对齐扯旋 v46)。
     */
    private int settleTimeMins;
    /** 抽水比例(%),周期结算时对盈利部分抽取 */
    private int rakePercent;

    /** 座位 → 玩家(座位号 0..maxPlayers-1) */
    private final DzPlayer[] seats;

    /** 房间内所有人(含站起的观众) userId → 昵称 */
    private final Map<Long, String> members = new ConcurrentHashMap<>();

    // ==================== 一手牌的牌局状态 ====================

    private GameStage stage = GameStage.WAITING;
    /** 手数编号(循环递增) */
    private long handNo;
    /** 庄位座位号 */
    private int button = -1;
    private int sbSeat = -1;
    private int bbSeat = -1;

    private Deck deck;
    private final List<Card> board = new ArrayList<>(5);

    /** 当前行动座位 */
    private int actingSeat = -1;
    /** 当前轮注额(跟到这个数) */
    private long currentBet;
    /** 最小加注增量 */
    private long minRaise;
    /** 本手已收进池的筹码(不含玩家面前未收的 betThisRound) */
    private long collectedPot;
    /** 结算切好的池 */
    private List<Pot> pots = new ArrayList<>();
    /**
     * 本手的"死钱":局中已弃牌先行站起的玩家,座位清了但投入必须留在池里,
     * 摊牌切池时并入 contributions(folded=true,只进池不参与分池)。
     */
    private final List<PotManager.Contribution> deadContributions = new ArrayList<>();

    /** 行动超时任务(换人行动时取消重排) */
    private ScheduledFuture<?> actionTimeout;
    /** 行动超时截止时间戳(快照给前端画倒计时) */
    private long actionDeadline;
    /** 下一手已排队(防重复调度) */
    private volatile boolean handScheduled;

    public DzRoom(int maxPlayers) {
        this.maxPlayers = maxPlayers;
        this.seats = new DzPlayer[maxPlayers];
    }

    public DzPlayer playerByUserId(long userId) {
        for (DzPlayer p : seats) {
            if (p != null && p.getUserId() == userId) return p;
        }
        return null;
    }

    public DzPlayer playerAtSeat(int seat) {
        return (seat >= 0 && seat < seats.length) ? seats[seat] : null;
    }

    /** 在座且有筹码、未暂离的人数(能开局的人) */
    public List<DzPlayer> readyPlayers() {
        List<DzPlayer> list = new ArrayList<>();
        for (DzPlayer p : seats) {
            if (p != null && p.getStack() > 0 && !p.isSittingOut() && !p.isPendingStandUp()) {
                list.add(p);
            }
        }
        return list;
    }

    /** 本手还在争夺的玩家 */
    public List<DzPlayer> contestingPlayers() {
        List<DzPlayer> list = new ArrayList<>();
        for (DzPlayer p : seats) {
            if (p != null && p.contesting()) list.add(p);
        }
        return list;
    }

    /** 从 seat 顺时针找下一个满足条件的座位,找不到返回 -1 */
    public int nextSeat(int fromSeat, java.util.function.Predicate<DzPlayer> cond) {
        for (int i = 1; i <= maxPlayers; i++) {
            int s = (fromSeat + i) % maxPlayers;
            DzPlayer p = seats[s];
            if (p != null && cond.test(p)) return s;
        }
        return -1;
    }

    /** 桌面总池(已收+各家面前) — 广播展示用 */
    public long displayPot() {
        long sum = collectedPot;
        for (DzPlayer p : seats) {
            if (p != null) sum += p.getBetThisRound();
        }
        return sum;
    }

    /** 游戏是否进行中(一手牌没打完) */
    public boolean inGame() {
        return stage != GameStage.WAITING && stage != GameStage.FINISHED;
    }
}
