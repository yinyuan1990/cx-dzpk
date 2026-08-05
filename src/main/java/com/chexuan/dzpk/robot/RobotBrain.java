package com.chexuan.dzpk.robot;

import com.chexuan.dzpk.game.card.BiPai;
import com.chexuan.dzpk.game.card.Card;
import com.chexuan.dzpk.game.card.HandResult;
import com.chexuan.dzpk.game.model.DzPlayer;
import com.chexuan.dzpk.game.model.DzRoom;
import com.chexuan.dzpk.game.model.GameStage;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 机器人决策大脑 — 复刻老德州 AiOperate/AiBipai/AiRuleTemplate 的核心模型:
 *   ① 上帝视角比牌(AiBipai.biPai):用完整 5 张公共牌(含 Deck 里还没发的)与所有
 *      未弃牌对手比牌 → isWin(平局算赢),从此"知道自己本手最终输赢";
 *   ② 赢/输两套行动概率表(preflopWin/Loss_action + flop/turn/riverWin/Loss_action 的精髓):
 *      赢局更凶(加注做池,永不弃),输局更怂(便宜看牌,贵了就跑,小概率诈唬);
 *   ③ 性格(EAiType 六型精简为三型):AGGRESSIVE 松凶 / BALANCE 平衡 / CONSERVATIVE 紧弱,
 *      同一机器人性格恒定(userId 哈希按俱乐部分布参数落桶);
 *   ④ 控盘介入(DzProfitControl):吃分手赢家更凶输家不喂;放水手赢家让牌、输家限预算喂池。
 */
public final class RobotBrain {

    public static final int PERSONA_CONSERVATIVE = 0;
    public static final int PERSONA_BALANCE = 1;
    public static final int PERSONA_AGGRESSIVE = 2;

    /** act: fold / check / call / raise;amount 仅 raise 用(raiseTo 目标额) */
    public record Decision(String act, long amount) { }

    private RobotBrain() {
    }

    /** 性格分配:userId 哈希落桶,分布来自参数(aggressive_prob/conservative_prob) */
    public static int personaOf(long userId, int aggressiveProb, int conservativeProb) {
        int h = (int) ((userId * 2654435761L >>> 16) % 100);
        if (h < aggressiveProb) return PERSONA_AGGRESSIVE;
        if (h < aggressiveProb + conservativeProb) return PERSONA_CONSERVATIVE;
        return PERSONA_BALANCE;
    }

    /**
     * 上帝视角比牌(对齐老德州 AiBipai.biPai):
     * 最终公共牌 = 已发 board + deck 接下来 (5-board) 张;与每个未弃牌对手比,平局算赢。
     */
    public static boolean isGodWin(DzRoom room, DzPlayer me) {
        try {
            List<Card> board = room.getBoard();
            List<Card> future = room.getDeck().peek(5 - board.size());
            if (board.size() + future.size() < 5) return false;
            Card[] common = new Card[5];
            for (int i = 0; i < board.size(); i++) common[i] = board.get(i);
            for (int i = 0; i < future.size(); i++) common[board.size() + i] = future.get(i);

            HandResult mine = BiPai.evaluate(seven(me.getHoleCards(), common));
            for (DzPlayer p : room.getSeats()) {
                if (p == null || p == me || !p.isInHand() || p.isFolded()) continue;
                HandResult other = BiPai.evaluate(seven(p.getHoleCards(), common));
                if (BiPai.compare(mine, other) == 1) return false; // b 大 → 我输
            }
            return true;
        } catch (Exception e) {
            return false; // 任何异常按输处理(保守)
        }
    }

    private static Card[] seven(Card[] hole, Card[] common) {
        Card[] out = new Card[7];
        out[0] = hole[0];
        out[1] = hole[1];
        System.arraycopy(common, 0, out, 2, 5);
        return out;
    }

    /**
     * 决策入口。
     *
     * @param persona    性格(personaOf)
     * @param bias       控盘方向:+1 吃分 / -1 放水 / 0 中性
     * @param budgetLeft 放水手剩余喂池预算(分)
     */
    public static Decision decide(DzRoom room, DzPlayer me, long toCall, long minRaiseTo,
                                  int persona, int bias, long budgetLeft) {
        boolean win = isGodWin(room, me);
        boolean preflop = room.getStage() == GameStage.PREFLOP;
        long bb = room.getBb();
        long stack = me.getStack();
        ThreadLocalRandom r = ThreadLocalRandom.current();

        // ---- 控盘强介入 ----
        if (bias == -1 && win) {
            // 放水:赢牌让出。有人下注 → 大概率弃牌;没人下注 → 过牌到底(绝不做池)
            if (toCall > 0) {
                return r.nextInt(100) < 85 ? new Decision("fold", 0) : new Decision("call", 0);
            }
            return new Decision("check", 0);
        }
        if (bias == -1 && !win) {
            // 放水:输牌喂池,但受预算限制;预算耗尽回到正常输局打法
            long feed = Math.max(toCall, bb);
            if (budgetLeft >= feed) {
                if (toCall > 0) {
                    // 大概率跟注喂;小概率加注多喂一点
                    if (r.nextInt(100) < 25 && minRaiseTo > 0 && minRaiseTo - me.getBetThisRound() < stack
                            && budgetLeft >= minRaiseTo) {
                        return new Decision("raise", minRaiseTo);
                    }
                    return new Decision("call", 0);
                }
                // 没人下注:小注开路给真人加注机会
                if (r.nextInt(100) < 40 && minRaiseTo > 0 && budgetLeft >= minRaiseTo) {
                    return new Decision("raise", minRaiseTo);
                }
                return new Decision("check", 0);
            }
            // 预算耗尽 → 走正常输局表
        }

        // ---- 正常表(老德州 Win/Loss 行动表精髓) + 吃分手加权 ----
        if (win) {
            return winAction(room, me, toCall, minRaiseTo, persona, bias == 1, preflop, bb, stack, r);
        }
        return lossAction(me, toCall, minRaiseTo, persona, bias == 1, preflop, bb, stack, r);
    }

    /** 赢局:永不弃牌;按性格决定做池力度(吃分手力度上调) */
    private static Decision winAction(DzRoom room, DzPlayer me, long toCall, long minRaiseTo, int persona,
                                      boolean boost, boolean preflop, long bb, long stack,
                                      ThreadLocalRandom r) {
        // 加注概率:紧弱慢玩 / 平衡适中 / 松凶做大池;吃分手统一上调
        int raiseProb = switch (persona) {
            case PERSONA_AGGRESSIVE -> preflop ? 45 : 60;
            case PERSONA_CONSERVATIVE -> preflop ? 15 : 25;  // 慢玩为主
            default -> preflop ? 30 : 40;
        };
        int allinProb = persona == PERSONA_AGGRESSIVE ? 6 : 2;
        if (boost) {
            raiseProb = Math.min(90, raiseProb + 25);
            allinProb += 4;
        }

        boolean canRaise = minRaiseTo > 0 && minRaiseTo - me.getBetThisRound() < stack;
        if (canRaise && r.nextInt(100) < allinProb) {
            return new Decision("raise", me.getBetThisRound() + stack); // 全下
        }
        if (canRaise && r.nextInt(100) < raiseProb) {
            // 加注尺度:min ~ min+3bb;吃分手偶尔按底池加(做大池)
            long to = minRaiseTo + bb * r.nextInt(0, 4);
            if (boost && r.nextInt(100) < 40) {
                to = Math.max(to, minRaiseTo + room.getCollectedPot() / 2);
            }
            return new Decision("raise", Math.min(to, me.getBetThisRound() + stack));
        }
        return toCall > 0 ? new Decision("call", 0) : new Decision("check", 0);
    }

    /** 输局:便宜看牌,贵了就跑;小概率诈唬;吃分手直接收紧(别喂) */
    private static Decision lossAction(DzPlayer me, long toCall, long minRaiseTo, int persona,
                                       boolean tighten, boolean preflop, long bb, long stack,
                                       ThreadLocalRandom r) {
        boolean canRaise = minRaiseTo > 0 && minRaiseTo - me.getBetThisRound() < stack;
        if (toCall <= 0) {
            // 免费看牌为主;小概率诈唬开路(松凶多一点)
            int bluff = persona == PERSONA_AGGRESSIVE ? 12 : persona == PERSONA_BALANCE ? 5 : 2;
            if (tighten) bluff = 1;
            if (canRaise && r.nextInt(100) < bluff) {
                return new Decision("raise", minRaiseTo);
            }
            return new Decision("check", 0);
        }
        boolean cheap = toCall <= bb * 2;
        int callProb;
        if (cheap) {
            callProb = switch (persona) {
                case PERSONA_AGGRESSIVE -> preflop ? 55 : 32;
                case PERSONA_CONSERVATIVE -> preflop ? 28 : 12;
                default -> preflop ? 40 : 20;
            };
        } else {
            callProb = persona == PERSONA_AGGRESSIVE ? 12 : 6; // 贵了基本跑
        }
        int bluffRaise = persona == PERSONA_AGGRESSIVE ? 8 : persona == PERSONA_BALANCE ? 3 : 1;
        if (tighten) {
            callProb /= 2;
            bluffRaise = 0;
        }
        if (canRaise && !cheap && toCall < stack && r.nextInt(100) < bluffRaise) {
            return new Decision("raise", minRaiseTo); // 反加诈唬
        }
        if (toCall < stack && r.nextInt(100) < callProb) {
            return new Decision("call", 0);
        }
        return new Decision("fold", 0);
    }
}
