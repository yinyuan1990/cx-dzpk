package com.chexuan.dzpk.db;

import com.chexuan.dzpk.game.card.Card;
import com.chexuan.dzpk.game.model.DzPlayer;
import com.chexuan.dzpk.game.model.DzRoom;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 战绩/结算落库(cx_dzpk 库,JdbcTemplate 直写)。
 * 写库失败只打日志,绝不打断牌局(牌局内存态是权威,库是记录)。
 * 测试可 new DzRecordStore(null) → 全部 no-op。
 */
@Slf4j
@Service
public class DzRecordStore {

    private final JdbcTemplate jdbc;

    public DzRecordStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ================================================================
    // 写入
    // ================================================================

    /** 房间创建档案 */
    public void saveRoomCreated(DzRoom room, long diamondCost) {
        if (jdbc == null) return;
        try {
            String rulesJson = "";
            if (room.getRules() != null) {
                try {
                    rulesJson = new com.fasterxml.jackson.databind.ObjectMapper()
                            .writeValueAsString(room.getRules().toMap());
                } catch (Exception ignored) {
                }
            }
            jdbc.update("INSERT INTO dz_room (room_id, name, club_id, creator_user_id, sb, bb, max_players, " +
                            "settle_time_mins, rake_percent, diamond_cost, rules_json, created_at) " +
                            "VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                    room.getRoomId(), room.getName(), room.getClubId(), room.getCreatorUserId(), room.getSb(),
                    room.getBb(), room.getMaxPlayers(), room.getSettleTimeMins(), room.getRakePercent(),
                    diamondCost, rulesJson, now());
        } catch (Exception e) {
            log.error("dz_room 写入失败: roomId={}", room.getRoomId(), e);
        }
    }

    /** 房间销毁 */
    public void markRoomClosed(long roomId) {
        if (jdbc == null) return;
        try {
            jdbc.update("UPDATE dz_room SET closed_at = ? WHERE room_id = ? AND closed_at IS NULL", now(), roomId);
        } catch (Exception e) {
            log.error("dz_room 关闭标记失败: roomId={}", roomId, e);
        }
    }

    /**
     * 房间历史结算带入总和(对齐扯旋 sumBringInByRoomId:实时战绩"总带入" =
     * 当前在座实时带入 + 历史周期结算记录带入;跨周期累计,一人循环几个周期都算进去)
     */
    public long sumRoomBringIn(long roomId) {
        if (jdbc == null) return 0;
        try {
            Long v = jdbc.queryForObject(
                    "SELECT COALESCE(SUM(bring_in), 0) FROM dz_settle_record WHERE room_id = ?", Long.class, roomId);
            return v != null ? v : 0;
        } catch (Exception e) {
            log.error("房间历史带入合计失败: roomId={}", roomId, e);
            return 0;
        }
    }

    /** 批量取用户简要资料(实时战绩:头像/6位ID;游客/大厅临时机器人查不到则缺省) */
    public java.util.Map<Long, java.util.Map<String, Object>> userBriefs(java.util.Collection<Long> ids) {
        java.util.Map<Long, java.util.Map<String, Object>> out = new java.util.HashMap<>();
        if (jdbc == null || ids == null || ids.isEmpty()) return out;
        try {
            String in = ids.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(","));
            for (java.util.Map<String, Object> row : jdbc.queryForList(
                    "SELECT id, nickname, avatar, number_id FROM dz_user WHERE id IN (" + in + ")")) {
                Object id = row.get("id") != null ? row.get("id") : row.get("ID");
                Object nick = row.get("nickname") != null ? row.get("nickname") : row.get("NICKNAME");
                Object av = row.get("avatar") != null ? row.get("avatar") : row.get("AVATAR");
                Object no = row.get("number_id") != null ? row.get("number_id") : row.get("NUMBER_ID");
                out.put(((Number) id).longValue(), java.util.Map.of(
                        "nickname", nick == null ? "" : nick,
                        "avatar", av == null ? "" : av,
                        "numberId", no == null ? "" : no));
            }
        } catch (Exception e) {
            log.error("用户简要资料查询失败", e);
        }
        return out;
    }

    /** 未关闭的房间(重启恢复用):roomId/creator/rules_json */
    public java.util.List<java.util.Map<String, Object>> openRooms() {
        if (jdbc == null) return java.util.List.of();
        try {
            return jdbc.queryForList(
                    "SELECT room_id, creator_user_id, rules_json FROM dz_room WHERE closed_at IS NULL ORDER BY id");
        } catch (Exception e) {
            log.error("dz_room 未关闭房间查询失败", e);
            return java.util.List.of();
        }
    }

    /** 一手结束:每个参与者一行明细(在 finishHand 时调用,netWin 已算好) */
    public void saveHandRecords(DzRoom room) {
        if (jdbc == null) return;
        try {
            String board = cardStr(room.getBoard().toArray(new Card[0]));
            List<Object[]> rows = new ArrayList<>();
            Timestamp ts = now();
            for (DzPlayer p : room.getSeats()) {
                if (p == null || !p.isInHand()) continue;
                rows.add(handRow(room, p, board, ts));
            }
            // 局中已弃牌先行站起的玩家(座位已清),由 doStandUp 单独补一行,这里不重复
            if (!rows.isEmpty()) {
                jdbc.batchUpdate("INSERT INTO dz_hand_record (room_id, hand_no, user_id, nickname, seat, " +
                        "period_seq, net_win, total_bet, stack_after, folded, showdown, hole_cards, board, " +
                        "hand_type, hand_name, created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", rows);
            }
        } catch (Exception e) {
            log.error("dz_hand_record 写入失败: roomId={}, handNo={}", room.getRoomId(), room.getHandNo(), e);
        }
    }

    /** 局中弃牌后立即站起的玩家:座位在 finishHand 前已清,单独补这一手的明细 */
    public void saveHandRecordForLeaver(DzRoom room, DzPlayer p) {
        if (jdbc == null) return;
        try {
            String board = cardStr(room.getBoard().toArray(new Card[0]));
            jdbc.update("INSERT INTO dz_hand_record (room_id, hand_no, user_id, nickname, seat, " +
                            "period_seq, net_win, total_bet, stack_after, folded, showdown, hole_cards, board, " +
                            "hand_type, hand_name, created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    handRow(room, p, board, now()));
        } catch (Exception e) {
            log.error("dz_hand_record(leaver) 写入失败: roomId={}, userId={}", room.getRoomId(), p.getUserId(), e);
        }
    }

    private Object[] handRow(DzRoom room, DzPlayer p, String board, Timestamp ts) {
        boolean showdown = p.getHandResult() != null;
        return new Object[]{
                room.getRoomId(), room.getHandNo(), p.getUserId(), p.getNickname(), p.getSeat(),
                p.getSettlePeriodSeq(), p.getNetWin(), p.getTotalBetThisHand(), p.getStack(),
                p.isFolded() ? 1 : 0, showdown ? 1 : 0,
                p.getHoleCards() != null ? cardStr(p.getHoleCards()) : null,
                board.isEmpty() ? null : board,
                showdown ? p.getHandResult().getType() : null,
                showdown ? p.getHandResult().typeName() : null,
                ts};
    }

    /**
     * 牌型回顾(对齐扯旋 CHEXUANTableRecordPanel):某一手的静态快照,每页一手。
     * handNo<=0 = 最近一手;可见性:请求者自己的手牌总是给,他人只有摊牌(showdown=1)才给;
     * forceShow=true(付费强制秀牌,费用在调用方扣)则全部给。
     * 返回 {handNo, minHandNo, maxHandNo, board, roundTime,
     *       players:[{seat,nickname,avatar,userId,holeCards,handName,netWin,totalBet,folded,showdown}]}
     */
    public java.util.Map<String, Object> handReview(long roomId, long handNo, long viewerUserId, boolean forceShow) {
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("handNo", 0L);
        out.put("minHandNo", 0L);
        out.put("maxHandNo", 0L);
        out.put("board", "");
        out.put("roundTime", 0L);
        out.put("players", java.util.List.of());
        if (jdbc == null) return out;
        try {
            java.util.Map<String, Object> range = jdbc.queryForMap(
                    "SELECT COALESCE(MIN(hand_no),0) AS mn, COALESCE(MAX(hand_no),0) AS mx " +
                            "FROM dz_hand_record WHERE room_id = ?", roomId);
            long mn = ((Number) col2(range, "mn")).longValue();
            long mx = ((Number) col2(range, "mx")).longValue();
            out.put("minHandNo", mn);
            out.put("maxHandNo", mx);
            if (mx <= 0) return out;
            long target = handNo <= 0 ? mx : Math.max(mn, Math.min(mx, handNo));
            java.util.List<java.util.Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT user_id, nickname, seat, net_win, total_bet, folded, showdown, hole_cards, board, " +
                            "hand_name, created_at FROM dz_hand_record WHERE room_id = ? AND hand_no = ? ORDER BY seat",
                    roomId, target);
            out.put("handNo", target);
            // 头像批量查(dz_user;游客/大厅临时机器人查不到则空)
            java.util.Set<Long> ids = new java.util.HashSet<>();
            for (java.util.Map<String, Object> r : rows) {
                ids.add(((Number) col2(r, "user_id")).longValue());
            }
            java.util.Map<Long, java.util.Map<String, Object>> briefs = userBriefs(ids);

            java.util.List<java.util.Map<String, Object>> players = new ArrayList<>();
            for (java.util.Map<String, Object> r : rows) {
                long uid = ((Number) col2(r, "user_id")).longValue();
                boolean showdown = ((Number) col2(r, "showdown")).intValue() == 1;
                Object board = col2(r, "board");
                if (board != null && String.valueOf(out.get("board")).isEmpty()) {
                    out.put("board", String.valueOf(board));
                }
                Object ts = col2(r, "created_at");
                if (ts instanceof Timestamp t && ((Number) out.get("roundTime")).longValue() == 0) {
                    out.put("roundTime", t.getTime());
                }
                java.util.Map<String, Object> p = new java.util.LinkedHashMap<>();
                p.put("userId", uid);
                p.put("nickname", col2(r, "nickname"));
                p.put("avatar", briefs.getOrDefault(uid, java.util.Map.of()).getOrDefault("avatar", ""));
                p.put("seat", ((Number) col2(r, "seat")).intValue());
                p.put("netWin", ((Number) col2(r, "net_win")).longValue());
                p.put("totalBet", ((Number) col2(r, "total_bet")).longValue());
                p.put("folded", ((Number) col2(r, "folded")).intValue() == 1);
                p.put("showdown", showdown);
                // 可见性(对齐扯旋):自己的牌总给;他人只有摊牌亮过才给;付费强制秀牌全给
                boolean visible = forceShow || uid == viewerUserId || showdown;
                Object hole = col2(r, "hole_cards");
                p.put("holeCards", visible && hole != null ? hole : "");
                p.put("handName", visible ? col2(r, "hand_name") : null);
                players.add(p);
            }
            out.put("players", players);
        } catch (Exception e) {
            log.error("牌型回顾查询失败: roomId={}, handNo={}", roomId, handNo, e);
        }
        return out;
    }

    private static Object col2(java.util.Map<String, Object> row, String key) {
        Object v = row.get(key);
        return v != null ? v : row.get(key.toUpperCase());
    }

    /** 周期/站起结算记录(战绩权威盈亏) */
    public void saveSettleRecord(DzRoom room, DzPlayer p, String reason,
                                 long bringIn, long finalStack, long profit,
                                 long rake, long refund, long playedSecs) {
        if (jdbc == null) return;
        try {
            jdbc.update("INSERT INTO dz_settle_record (room_id, room_name, club_id, user_id, nickname, period_seq, " +
                            "bring_in, final_stack, profit, rake, refund, hand_count, win_count, lose_count, " +
                            "played_secs, reason, created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    room.getRoomId(), room.getName(), room.getClubId(), p.getUserId(), p.getNickname(),
                    p.getSettlePeriodSeq(), bringIn, finalStack, profit, rake, refund,
                    p.getHandCount(), p.getWinCount(), p.getLoseCount(), playedSecs, reason, now());
        } catch (Exception e) {
            log.error("dz_settle_record 写入失败: roomId={}, userId={}, reason={}",
                    room.getRoomId(), p.getUserId(), reason, e);
        }
    }

    // ================================================================
    // 查询(战绩页)
    // ================================================================

    /** 我的战绩:最近 limit 条周期/站起结算;clubId>0 = 只看该俱乐部(对齐扯旋 gameSummary) */
    public List<Map<String, Object>> myRecords(long userId, int limit, long clubId) {
        if (jdbc == null) return List.of();
        try {
            String clubCond = clubId > 0 ? " AND club_id = " + clubId : "";
            return jdbc.query("SELECT room_id, room_name, club_id, period_seq, bring_in, final_stack, profit, rake, " +
                            "refund, hand_count, win_count, lose_count, played_secs, reason, created_at " +
                            "FROM dz_settle_record WHERE user_id = ?" + clubCond +
                            " ORDER BY id DESC LIMIT " + Math.min(Math.max(limit, 1), 500),
                    (rs, i) -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("roomId", rs.getLong("room_id"));
                        m.put("clubId", rs.getLong("club_id"));
                        m.put("roomName", rs.getString("room_name"));
                        m.put("periodSeq", rs.getInt("period_seq"));
                        m.put("bringIn", rs.getLong("bring_in"));
                        m.put("finalStack", rs.getLong("final_stack"));
                        m.put("profit", rs.getLong("profit"));
                        m.put("rake", rs.getLong("rake"));
                        m.put("refund", rs.getLong("refund"));
                        m.put("handCount", rs.getInt("hand_count"));
                        m.put("winCount", rs.getInt("win_count"));
                        m.put("loseCount", rs.getInt("lose_count"));
                        m.put("playedSecs", rs.getLong("played_secs"));
                        m.put("reason", rs.getString("reason"));
                        m.put("time", rs.getTimestamp("created_at").getTime());
                        return m;
                    }, userId);
        } catch (Exception e) {
            log.error("战绩查询失败: userId={}", userId, e);
            return List.of();
        }
    }

    /** 本房间最近结算记录(实时战绩历史区,对齐扯旋同 session 最近 50 条) */
    public List<Map<String, Object>> roomRecords(long roomId, int limit) {
        if (jdbc == null) return List.of();
        try {
            return jdbc.query("SELECT user_id, nickname, period_seq, bring_in, final_stack, profit, rake, " +
                            "refund, hand_count, win_count, lose_count, played_secs, reason, created_at " +
                            "FROM dz_settle_record WHERE room_id = ? ORDER BY id DESC LIMIT " + Math.min(limit, 100),
                    (rs, i) -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("userId", rs.getLong("user_id"));
                        m.put("nickname", rs.getString("nickname"));
                        m.put("periodSeq", rs.getInt("period_seq"));
                        m.put("bringIn", rs.getLong("bring_in"));
                        m.put("finalStack", rs.getLong("final_stack"));
                        m.put("profit", rs.getLong("profit"));
                        m.put("rake", rs.getLong("rake"));
                        m.put("refund", rs.getLong("refund"));
                        m.put("handCount", rs.getInt("hand_count"));
                        m.put("winCount", rs.getInt("win_count"));
                        m.put("loseCount", rs.getInt("lose_count"));
                        m.put("playedSecs", rs.getLong("played_secs"));
                        m.put("reason", rs.getString("reason"));
                        m.put("time", rs.getTimestamp("created_at").getTime());
                        return m;
                    }, roomId);
        } catch (Exception e) {
            log.error("房间战绩查询失败: roomId={}", roomId, e);
            return List.of();
        }
    }

    /** 我的累计战绩:总场次/总盈亏/总手数/胜负;clubId>0 = 只看该俱乐部 */
    public Map<String, Object> myStats(long userId, long clubId) {
        if (jdbc == null) return Map.of();
        try {
            String clubCond = clubId > 0 ? " AND club_id = " + clubId : "";
            return jdbc.queryForObject("SELECT COUNT(*) AS sessions, COALESCE(SUM(profit),0) AS totalProfit, " +
                            "COALESCE(SUM(hand_count),0) AS totalHands, COALESCE(SUM(win_count),0) AS totalWins, " +
                            "COALESCE(SUM(lose_count),0) AS totalLoses, COALESCE(SUM(rake),0) AS totalRake, " +
                            "COALESCE(SUM(CASE WHEN profit > 0 THEN 1 ELSE 0 END),0) AS winSessions, " +
                            "COALESCE(SUM(CASE WHEN profit < 0 THEN 1 ELSE 0 END),0) AS loseSessions " +
                            "FROM dz_settle_record WHERE user_id = ?" + clubCond,
                    (rs, i) -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("sessions", rs.getLong("sessions"));
                        m.put("totalProfit", rs.getLong("totalProfit"));
                        m.put("totalHands", rs.getLong("totalHands"));
                        m.put("totalWins", rs.getLong("totalWins"));
                        m.put("totalLoses", rs.getLong("totalLoses"));
                        m.put("totalRake", rs.getLong("totalRake"));
                        m.put("winSessions", rs.getLong("winSessions"));
                        m.put("loseSessions", rs.getLong("loseSessions"));
                        return m;
                    }, userId);
        } catch (Exception e) {
            log.error("战绩汇总失败: userId={}", userId, e);
            return Map.of();
        }
    }

    // ================================================================

    private static Timestamp now() {
        return new Timestamp(System.currentTimeMillis());
    }

    private static String cardStr(Card[] cards) {
        StringBuilder sb = new StringBuilder();
        for (Card c : cards) {
            if (c == null) continue;
            if (sb.length() > 0) sb.append(',');
            sb.append(c);
        }
        return sb.toString();
    }
}
