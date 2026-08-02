package com.chexuan.dzpk.ws;

import com.chexuan.dzpk.auth.JwtVerifier;
import com.chexuan.dzpk.db.DiamondService;
import com.chexuan.dzpk.db.DzRecordStore;
import com.chexuan.dzpk.game.model.DzRoom;
import com.chexuan.dzpk.game.service.DzGameService;
import com.chexuan.dzpk.game.service.DzRoomManager;
import com.chexuan.dzpk.game.service.WalletService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 德州 WS 入口 — 协议:GameMessage JSON 信封,命令号 4xx 段。
 * 登录后所有房间操作转 DzGameService(内部按房间串行)。
 */
@Slf4j
@Component
public class DzWebSocketHandler extends TextWebSocketHandler {

    private static final String ATTR_USER_ID = "dzpkUserId";
    private static final String ATTR_NICKNAME = "dzpkNickname";

    /** 游客 id 段,与主服真实 userId 区分 */
    private final AtomicLong guestIdGen = new AtomicLong(900_000_001L);

    private final ObjectMapper objectMapper;
    private final JwtVerifier jwtVerifier;
    private final WsSessionRegistry registry;
    private final DzGameService gameService;
    private final DzRoomManager roomManager;
    private final WalletService walletService;
    private final DiamondService diamondService;
    private final DzRecordStore records;

    @Value("${dzpk.allow-guest:false}")
    private boolean allowGuest;

    /** 创建房间扣钻石(0=不扣;游客/机器人无主服账号,不扣) */
    @Value("${dzpk.create-room-diamond-cost:0}")
    private long createRoomDiamondCost;

    public DzWebSocketHandler(ObjectMapper objectMapper, JwtVerifier jwtVerifier,
                              WsSessionRegistry registry, DzGameService gameService,
                              DzRoomManager roomManager, WalletService walletService,
                              DiamondService diamondService, DzRecordStore records) {
        this.objectMapper = objectMapper;
        this.jwtVerifier = jwtVerifier;
        this.registry = registry;
        this.gameService = gameService;
        this.roomManager = roomManager;
        this.walletService = walletService;
        this.diamondService = diamondService;
        this.records = records;
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        GameMessage msg;
        try {
            msg = objectMapper.readValue(message.getPayload(), GameMessage.class);
        } catch (Exception e) {
            send(session, err(null, "消息格式错误"));
            return;
        }
        if (msg.getType() == null) {
            send(session, err(msg, "缺少 type"));
            return;
        }

        try {
            if (msg.getType() == MsgType.LOGIN) {
                handleLogin(session, msg);
                return;
            }
            Long userId = (Long) session.getAttributes().get(ATTR_USER_ID);
            if (userId == null) {
                send(session, err(msg, "请先登录"));
                return;
            }
            dispatch(session, userId, msg);
        } catch (Exception e) {
            log.error("消息处理异常: type={}", msg.getType(), e);
            send(session, err(msg, "服务器内部错误"));
        }
    }

    private void handleLogin(WebSocketSession session, GameMessage msg) {
        Map<String, Object> data = dataMap(msg);
        Long userId = null;
        String nickname = null;

        String token = str(data, "token");
        if (token != null && !token.isBlank()) {
            userId = jwtVerifier.verify(token);
            if (userId == null) {
                send(session, err(msg, "token 无效或已过期"));
                return;
            }
            nickname = str(data, "nickname");
            if (nickname == null) nickname = "玩家" + userId;
        } else if (allowGuest && str(data, "guest") != null) {
            userId = guestIdGen.getAndIncrement();
            nickname = str(data, "guest");
        } else {
            send(session, err(msg, "缺少 token"));
            return;
        }

        session.getAttributes().put(ATTR_USER_ID, userId);
        session.getAttributes().put(ATTR_NICKNAME, nickname);
        registry.bind(userId, session);

        GameMessage res = GameMessage.create(MsgType.LOGIN_RES, null, Map.of(
                "userId", userId, "nickname", nickname,
                "balance", walletService.balance(userId),
                "diamond", diamondService.balance(userId)));
        res.setSequence(msg.getSequence());
        send(session, res);
        log.info("登录: userId={}, nickname={}", userId, nickname);
    }

    private void dispatch(WebSocketSession session, long userId, GameMessage msg) {
        Map<String, Object> data = dataMap(msg);
        long roomId = msg.getRoomId() != null ? msg.getRoomId() : 0;
        String nickname = (String) session.getAttributes().getOrDefault(ATTR_NICKNAME, "玩家" + userId);

        switch (msg.getType()) {
            case MsgType.ROOM_LIST -> {
                List<Map<String, Object>> list = new ArrayList<>();
                for (DzRoom r : roomManager.list()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("roomId", r.getRoomId());
                    m.put("name", r.getName());
                    m.put("sb", r.getSb());
                    m.put("bb", r.getBb());
                    m.put("maxPlayers", r.getMaxPlayers());
                    m.put("settleTimeMins", r.getSettleTimeMins());
                    int seated = 0;
                    for (var p : r.getSeats()) if (p != null) seated++;
                    m.put("seated", seated);
                    m.put("stage", r.getStage().name());
                    list.add(m);
                }
                GameMessage res = GameMessage.create(MsgType.ROOM_LIST_RES, null, Map.of("rooms", list));
                res.setSequence(msg.getSequence());
                send(session, res);
            }
            case MsgType.CREATE_ROOM -> {
                long sb = lng(data, "sb", 1);
                long bb = lng(data, "bb", sb * 2);
                int maxPlayers = (int) lng(data, "maxPlayers", 9);
                int settleTimeMins = (int) lng(data, "settleTimeMins", 30);
                int rakePercent = (int) lng(data, "rakePercent", 5);
                String name = str(data, "name");
                if (name == null || name.isBlank()) name = nickname + "的牌局";
                if (sb <= 0 || bb < sb || maxPlayers < 2 || maxPlayers > 9
                        || settleTimeMins < 0 || rakePercent < 0 || rakePercent > 20) {
                    send(session, err(msg, "创建参数非法"));
                    return;
                }
                // 建房扣钻石(公用货币,主库 user.diamond;游客/机器人无主服账号跳过)
                long cost = 0;
                if (createRoomDiamondCost > 0 && diamondService.hasMainAccount(userId)) {
                    if (!diamondService.debit(userId, createRoomDiamondCost, "create_room", "德州建房")) {
                        send(session, err(msg, "钻石不足,创建房间需要 " + createRoomDiamondCost + " 钻石"));
                        return;
                    }
                    cost = createRoomDiamondCost;
                }
                DzRoom room = roomManager.create(name, userId, sb, bb, maxPlayers, settleTimeMins, rakePercent);
                records.saveRoomCreated(room, cost);
                Map<String, Object> resData = new LinkedHashMap<>();
                resData.put("roomId", room.getRoomId());
                resData.put("name", room.getName());
                resData.put("sb", sb);
                resData.put("bb", bb);
                resData.put("maxPlayers", maxPlayers);
                resData.put("settleTimeMins", settleTimeMins);
                resData.put("rakePercent", rakePercent);
                resData.put("minBuyin", room.getMinBuyin());
                resData.put("maxBuyin", room.getMaxBuyin());
                resData.put("diamondCost", cost);
                resData.put("diamond", diamondService.balance(userId));
                GameMessage res = GameMessage.create(MsgType.CREATE_ROOM_RES, room.getRoomId(), resData);
                res.setSequence(msg.getSequence());
                send(session, res);
                log.info("创建房间: roomId={}, name={}, sb/bb={}/{}, settle={}min, rake={}%, 钻石={}",
                        room.getRoomId(), name, sb, bb, settleTimeMins, rakePercent, cost);
            }
            case MsgType.ENTER_ROOM -> gameService.enterRoom(roomId, userId, nickname);
            case MsgType.LEAVE_ROOM -> gameService.leaveRoom(roomId, userId);
            case MsgType.SIT_DOWN -> gameService.sitDown(roomId, userId, (int) lng(data, "seat", -1));
            case MsgType.BUY_IN -> gameService.buyIn(roomId, userId, lng(data, "amount", 0));
            case MsgType.STAND_UP -> gameService.standUp(roomId, userId);
            case MsgType.ACTION -> gameService.action(roomId, userId, str(data, "act"), lng(data, "amount", 0));
            case MsgType.SNAPSHOT -> gameService.snapshotTo(roomId, userId);
            case MsgType.MY_RECORDS -> {
                int limit = (int) lng(data, "limit", 20);
                GameMessage res = GameMessage.create(MsgType.MY_RECORDS_RES, null, Map.of(
                        "records", records.myRecords(userId, limit),
                        "stats", records.myStats(userId)));
                res.setSequence(msg.getSequence());
                send(session, res);
            }
            default -> send(session, err(msg, "未知命令 " + msg.getType()));
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Long userId = (Long) session.getAttributes().get(ATTR_USER_ID);
        if (userId != null) {
            registry.unbind(userId, session);
            // 掉线不踢座:轮到他行动时超时自动过/弃,重连发 SNAPSHOT 恢复
            log.info("断开: userId={}, status={}", userId, status.getCode());
        }
    }

    // ==================== 工具 ====================

    @SuppressWarnings("unchecked")
    private Map<String, Object> dataMap(GameMessage msg) {
        if (msg.getData() instanceof Map) {
            return (Map<String, Object>) msg.getData();
        }
        return new HashMap<>();
    }

    private String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v != null ? v.toString() : null;
    }

    private long lng(Map<String, Object> m, String key, long def) {
        Object v = m.get(key);
        if (v instanceof Number n) return n.longValue();
        if (v instanceof String s) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return def;
    }

    private GameMessage err(GameMessage req, String text) {
        GameMessage m = GameMessage.create(MsgType.ERROR,
                req != null ? req.getRoomId() : null, Map.of("msg", text));
        if (req != null) m.setSequence(req.getSequence());
        return m;
    }

    private void send(WebSocketSession session, GameMessage msg) {
        try {
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(msg)));
        } catch (Exception e) {
            log.warn("发送失败: {}", e.getMessage());
        }
    }
}
