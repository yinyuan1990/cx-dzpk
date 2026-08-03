package com.chexuan.dzpk.gift;

import com.chexuan.dzpk.club.DzClubService;
import com.chexuan.dzpk.db.DiamondService;
import com.chexuan.dzpk.game.model.DzPlayer;
import com.chexuan.dzpk.game.model.DzRoom;
import com.chexuan.dzpk.game.service.DzRoomManager;
import com.chexuan.dzpk.game.service.GameBroadcaster;
import com.chexuan.dzpk.game.service.RoomWorkerService;
import com.chexuan.dzpk.ws.GameMessage;
import com.chexuan.dzpk.ws.MsgType;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 房间送礼(1:1 对齐扯旋 GiftService/GiftConfigInitializer):
 *   礼物配置落库 dz_gift_config,后台可增删改价;启动按 gift_key 补齐缺失项(不覆盖后台修改)。
 *   扣费源三选一(costType):
 *     - SCORE      扣发送方桌面带入 stack
 *     - CLUB_SCORE 扣发送方俱乐部积分(流水 type=18 礼物赠送)
 *     - DIAMOND    扣发送方主服钻石
 *   未显式配置时:俱乐部房默认 CLUB_SCORE,非俱乐部房退回 SCORE(对齐扯旋 v52.9f)。
 *   扣费去向:消失(仅播放动画,不进任何账户)。
 *   送礼成功广播 ROOM_GIFT(对齐扯旋 351)。
 */
@Slf4j
@Service
public class DzGiftService {

    /** 默认礼物 — gift_key 直接用前端动画键(cx-dzpk-pixi gifts.js),价位对齐扯旋 100~500 档 */
    private record DefaultGift(String giftKey, String name, long costScore, int sortNo) {}

    private static final DefaultGift[] DEFAULTS = {
            new DefaultGift("meigui", "玫瑰", 100, 0),
            new DefaultGift("dianzan", "点赞", 100, 1),
            new DefaultGift("kiss", "飞吻", 100, 2),
            new DefaultGift("zhuaji", "抓鸡", 100, 3),
            new DefaultGift("xihongshi", "西红柿", 150, 4),
            new DefaultGift("poshui", "泼水", 150, 5),
            new DefaultGift("motou", "魔头", 150, 6),
            new DefaultGift("buyu", "捕鱼", 200, 7),
            new DefaultGift("zhadan", "炸弹", 300, 8),
            new DefaultGift("huojiantong", "火箭筒", 500, 9),
    };

    private final JdbcTemplate jdbc;
    private final DzRoomManager roomManager;
    private final RoomWorkerService roomWorker;
    private final GameBroadcaster broadcaster;
    private final DzClubService clubService;
    private final DiamondService diamondService;

    public DzGiftService(JdbcTemplate jdbc, DzRoomManager roomManager, RoomWorkerService roomWorker,
                         GameBroadcaster broadcaster, DzClubService clubService, DiamondService diamondService) {
        this.jdbc = jdbc;
        this.roomManager = roomManager;
        this.roomWorker = roomWorker;
        this.broadcaster = broadcaster;
        this.clubService = clubService;
        this.diamondService = diamondService;
    }

    /** 启动补齐默认礼物(对齐扯旋 GiftConfigInitializer:只补缺失,不覆盖后台修改) */
    @PostConstruct
    public void seedDefaults() {
        if (jdbc == null) return;
        try {
            int inserted = 0;
            for (DefaultGift d : DEFAULTS) {
                int n = jdbc.update("INSERT INTO dz_gift_config (gift_key, name, cost_score, cost_type, " +
                                "icon_url, anim_key, enabled, sort_no) " +
                                "SELECT ?, ?, ?, '', '', ?, 1, ? FROM DUAL WHERE NOT EXISTS " +
                                "(SELECT 1 FROM dz_gift_config WHERE gift_key = ?)",
                        d.giftKey(), d.name(), d.costScore(), d.giftKey(), d.sortNo(), d.giftKey());
                inserted += n;
            }
            log.info("默认礼物配置就绪(新增 {} 条)", inserted);
        } catch (Exception e) {
            log.error("默认礼物初始化失败", e);
        }
    }

    // ==================== 配置查询 ====================

    /** 上架礼物列表(玩家拉取) */
    public List<Map<String, Object>> listEnabled() {
        return listGifts("WHERE enabled = 1");
    }

    /** 全量礼物列表(管理后台) */
    public List<Map<String, Object>> listAll() {
        return listGifts("");
    }

    private List<Map<String, Object>> listGifts(String where) {
        if (jdbc == null) return new ArrayList<>();
        return jdbc.query("SELECT id, gift_key, name, cost_score, cost_type, icon_url, anim_key, enabled, sort_no " +
                        "FROM dz_gift_config " + where + " ORDER BY sort_no, id",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getLong("id"));
                    m.put("giftKey", rs.getString("gift_key"));
                    m.put("name", rs.getString("name"));
                    m.put("costScore", rs.getLong("cost_score"));
                    m.put("costType", rs.getString("cost_type"));
                    m.put("iconUrl", rs.getString("icon_url"));
                    m.put("animKey", rs.getString("anim_key"));
                    m.put("enabled", rs.getInt("enabled") == 1);
                    m.put("sortNo", rs.getInt("sort_no"));
                    return m;
                });
    }

    private Map<String, Object> byId(long giftId) {
        List<Map<String, Object>> list = jdbc.query(
                "SELECT id, gift_key, name, cost_score, cost_type, anim_key, enabled " +
                        "FROM dz_gift_config WHERE id = ?",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getLong("id"));
                    m.put("giftKey", rs.getString("gift_key"));
                    m.put("name", rs.getString("name"));
                    m.put("costScore", rs.getLong("cost_score"));
                    m.put("costType", rs.getString("cost_type"));
                    m.put("animKey", rs.getString("anim_key"));
                    m.put("enabled", rs.getInt("enabled") == 1);
                    return m;
                }, giftId);
        return list.isEmpty() ? null : list.get(0);
    }

    // ==================== 送礼(对齐扯旋 sendRoomGift) ====================

    /** 房间送礼:校验/扣费/广播都在 roomWorker 内串行执行 */
    public void sendRoomGift(long roomId, long fromUserId, long giftId, long toUserId) {
        DzRoom room = roomManager.get(roomId);
        if (room == null) {
            sendError(fromUserId, roomId, "房间不存在");
            return;
        }
        roomWorker.submit(roomId, () -> doSendGift(room, fromUserId, giftId, toUserId));
    }

    private void doSendGift(DzRoom room, long fromUserId, long giftId, long toUserId) {
        long roomId = room.getRoomId();
        DzPlayer from = room.playerByUserId(fromUserId);
        if (from == null) {
            sendError(fromUserId, roomId, "你未坐下,无法送礼");
            return;
        }
        DzPlayer to = null;
        if (toUserId > 0) {
            to = room.playerByUserId(toUserId);
            if (to == null) {
                sendError(fromUserId, roomId, "接收人不在桌上");
                return;
            }
            if (toUserId == fromUserId) {
                sendError(fromUserId, roomId, "不能给自己送礼");
                return;
            }
        }
        Map<String, Object> gift;
        try {
            gift = byId(giftId);
        } catch (Exception e) {
            log.error("礼物查询失败: giftId={}", giftId, e);
            sendError(fromUserId, roomId, "礼物查询失败");
            return;
        }
        if (gift == null || !Boolean.TRUE.equals(gift.get("enabled"))) {
            sendError(fromUserId, roomId, "礼物不存在或已下架");
            return;
        }
        long cost = (long) gift.get("costScore");
        if (cost < 0) {
            sendError(fromUserId, roomId, "礼物配置错误");
            return;
        }

        // 未显式配置:俱乐部房默认 CLUB_SCORE,非俱乐部房退回 SCORE(对齐扯旋 v52.9f)
        String costType = String.valueOf(gift.get("costType") == null ? "" : gift.get("costType")).trim();
        boolean clubRoom = room.getClubId() > 0 && clubService != null;
        if (costType.isEmpty()) {
            costType = clubRoom ? "CLUB_SCORE" : "SCORE";
        }

        long fromStack = from.getStack();
        if ("DIAMOND".equalsIgnoreCase(costType)) {
            if (diamondService == null
                    || !diamondService.debit(fromUserId, cost, "ROOM_GIFT",
                            "房间送礼: roomId=" + roomId + ", giftId=" + giftId
                                    + (toUserId > 0 ? ", to=" + toUserId : ""))) {
                sendError(fromUserId, roomId, "钻石不足,送此礼物需 " + cost);
                return;
            }
        } else if ("CLUB_SCORE".equalsIgnoreCase(costType)) {
            if (!clubRoom) {
                sendError(fromUserId, roomId, "非俱乐部房间不能用俱乐部积分送礼");
                return;
            }
            try {
                clubService.debitScoreForGift(room.getClubId(), fromUserId, cost,
                        "房间送礼: giftId=" + giftId + ", cost=" + cost
                                + (toUserId > 0 ? ", to=" + toUserId : ""));
            } catch (Exception e) {
                sendError(fromUserId, roomId, e.getMessage() == null ? "俱乐部积分不足" : e.getMessage());
                return;
            }
        } else {
            // SCORE: 扣桌面带入
            if (fromStack < cost) {
                sendError(fromUserId, roomId, "桌面筹码不足,送此礼物需 " + cost + ",你只有 " + fromStack);
                return;
            }
            fromStack = fromStack - cost;
            from.setStack(fromStack);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("fromUserId", fromUserId);
        data.put("fromSeat", from.getSeat());
        data.put("fromNickname", from.getNickname());
        data.put("fromStack", fromStack);
        if (to != null) {
            data.put("toUserId", toUserId);
            data.put("toSeat", to.getSeat());
            data.put("toNickname", to.getNickname());
        }
        data.put("giftId", gift.get("id"));
        data.put("giftKey", gift.get("giftKey"));
        data.put("giftName", gift.get("name"));
        String animKey = String.valueOf(gift.get("animKey") == null ? "" : gift.get("animKey"));
        data.put("animKey", animKey.isBlank() ? gift.get("giftKey") : animKey);
        data.put("cost", cost);
        data.put("costType", costType);
        data.put("timestamp", System.currentTimeMillis());
        broadcaster.toRoom(roomId, GameMessage.create(MsgType.ROOM_GIFT, roomId, data));
        log.info("[Gift] 送礼: roomId={}, from={}, to={}, gift={}({}), cost={}, costType={}",
                roomId, fromUserId, toUserId, giftId, gift.get("giftKey"), cost, costType);
    }

    private void sendError(long userId, long roomId, String msg) {
        broadcaster.toUser(userId, GameMessage.create(MsgType.ERROR, roomId, Map.of("msg", msg)));
    }

    // ==================== 管理后台 CRUD(对齐扯旋 saveGift/deleteGift) ====================

    /** 新建/更新礼物配置 */
    public Map<String, Object> save(Map<String, Object> in) {
        String giftKey = String.valueOf(in.getOrDefault("giftKey", "")).trim();
        String name = String.valueOf(in.getOrDefault("name", "")).trim();
        if (giftKey.isEmpty()) throw new IllegalArgumentException("giftKey 不能为空");
        if (name.isEmpty()) throw new IllegalArgumentException("礼物名不能为空");
        long costScore = Long.parseLong(String.valueOf(in.getOrDefault("costScore", "0")));
        if (costScore < 0) throw new IllegalArgumentException("costScore 必须 ≥ 0");
        String costType = String.valueOf(in.getOrDefault("costType", "")).trim().toUpperCase();
        if (!costType.isEmpty() && !"SCORE".equals(costType)
                && !"CLUB_SCORE".equals(costType) && !"DIAMOND".equals(costType)) {
            throw new IllegalArgumentException("costType 必须是 SCORE / CLUB_SCORE / DIAMOND 或留空(自动)");
        }
        String iconUrl = String.valueOf(in.getOrDefault("iconUrl", ""));
        String animKey = String.valueOf(in.getOrDefault("animKey", ""));
        boolean enabled = !"false".equalsIgnoreCase(String.valueOf(in.getOrDefault("enabled", "true")));
        int sortNo = Integer.parseInt(String.valueOf(in.getOrDefault("sortNo", "0")));

        Object idObj = in.get("id");
        long id = idObj == null || String.valueOf(idObj).isBlank() ? 0 : Long.parseLong(String.valueOf(idObj));
        // giftKey 唯一性
        Long dup = jdbc.query("SELECT id FROM dz_gift_config WHERE gift_key = ?",
                rs -> rs.next() ? rs.getLong(1) : null, giftKey);
        if (dup != null && dup != id) throw new IllegalArgumentException("giftKey 已被使用: " + giftKey);

        if (id > 0) {
            int n = jdbc.update("UPDATE dz_gift_config SET gift_key=?, name=?, cost_score=?, cost_type=?, " +
                            "icon_url=?, anim_key=?, enabled=?, sort_no=? WHERE id=?",
                    giftKey, name, costScore, costType, iconUrl, animKey, enabled ? 1 : 0, sortNo, id);
            if (n <= 0) throw new IllegalArgumentException("礼物不存在: id=" + id);
        } else {
            jdbc.update("INSERT INTO dz_gift_config (gift_key, name, cost_score, cost_type, icon_url, " +
                            "anim_key, enabled, sort_no) VALUES (?,?,?,?,?,?,?,?)",
                    giftKey, name, costScore, costType, iconUrl, animKey, enabled ? 1 : 0, sortNo);
        }
        return Map.of("giftKey", giftKey);
    }

    public void delete(long id) {
        jdbc.update("DELETE FROM dz_gift_config WHERE id = ?", id);
    }
}
