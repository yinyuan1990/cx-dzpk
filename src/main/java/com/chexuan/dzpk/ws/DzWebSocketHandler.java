package com.chexuan.dzpk.ws;

import com.chexuan.dzpk.auth.JwtVerifier;
import com.chexuan.dzpk.club.DzClubService;
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
    private static final String ATTR_AVATAR = "dzpkAvatar";

    /** 游客 id 段,与主服真实 userId 区分 */
    private final AtomicLong guestIdGen = new AtomicLong(900_000_001L);

    private final ObjectMapper objectMapper;
    private final JwtVerifier jwtVerifier;
    private final com.chexuan.dzpk.auth.DzUserService userService;
    private final WsSessionRegistry registry;
    private final DzGameService gameService;
    private final DzRoomManager roomManager;
    private final WalletService walletService;
    private final DiamondService diamondService;
    private final DzRecordStore records;
    private final DzClubService clubService;
    private final com.chexuan.dzpk.config.DzConfigService cfg;
    private final com.chexuan.dzpk.gift.DzGiftService giftService;
    private final com.chexuan.dzpk.game.service.GpsService gpsService;

    @Value("${dzpk.allow-guest:false}")
    private boolean allowGuest;

    /** 创建房间扣钻石(0=不扣;游客/机器人无主服账号,不扣) */
    @Value("${dzpk.create-room-diamond-cost:0}")
    private long createRoomDiamondCost;

    public DzWebSocketHandler(ObjectMapper objectMapper, JwtVerifier jwtVerifier,
                              WsSessionRegistry registry, DzGameService gameService,
                              DzRoomManager roomManager, WalletService walletService,
                              DiamondService diamondService, DzRecordStore records,
                              DzClubService clubService, com.chexuan.dzpk.config.DzConfigService cfg,
                              com.chexuan.dzpk.gift.DzGiftService giftService,
                              com.chexuan.dzpk.game.service.GpsService gpsService,
                              com.chexuan.dzpk.auth.DzUserService userService) {
        this.objectMapper = objectMapper;
        this.jwtVerifier = jwtVerifier;
        this.userService = userService;
        this.registry = registry;
        this.gameService = gameService;
        this.roomManager = roomManager;
        this.walletService = walletService;
        this.diamondService = diamondService;
        this.records = records;
        this.clubService = clubService;
        this.cfg = cfg;
        this.giftService = giftService;
        this.gpsService = gpsService;
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
        } catch (DzClubService.ClubException e) {
            send(session, err(msg, e.getMessage()));
        } catch (Exception e) {
            log.error("消息处理异常: type={}", msg.getType(), e);
            send(session, err(msg, "服务器内部错误"));
        }
    }

    private void handleLogin(WebSocketSession session, GameMessage msg) {
        Map<String, Object> data = dataMap(msg);
        Long userId = null;
        String nickname = null;
        String avatar = "";
        String numberId = "";

        String token = str(data, "token");
        if (token != null && !token.isBlank()) {
            userId = jwtVerifier.verify(token);
            if (userId == null) {
                send(session, err(msg, "token 无效或已过期"));
                return;
            }
            // 独立账号:昵称/头像以本地 dz_user 为准,不信客户端传值
            Map<String, Object> prof = userService.profile(userId);
            if (prof == null) {
                send(session, err(msg, "账号不存在或已被封禁"));
                return;
            }
            nickname = (String) prof.get("nickname");
            avatar = (String) prof.get("avatar");
            numberId = String.valueOf(prof.getOrDefault("numberId", ""));
        } else if (cfg.getBool("allow_guest", allowGuest) && str(data, "guest") != null) {
            userId = guestIdGen.getAndIncrement();
            nickname = str(data, "guest");
        } else {
            send(session, err(msg, "缺少 token"));
            return;
        }

        session.getAttributes().put(ATTR_USER_ID, userId);
        session.getAttributes().put(ATTR_NICKNAME, nickname);
        session.getAttributes().put(ATTR_AVATAR, avatar);
        registry.bind(userId, session);

        GameMessage res = GameMessage.create(MsgType.LOGIN_RES, null, Map.of(
                "userId", userId, "nickname", nickname, "avatar", avatar,
                "numberId", numberId,
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
                // clubId=0 公开房;clubId>0 该俱乐部的房间(须是成员)
                long clubId = lng(data, "clubId", 0);
                if (clubId > 0 && !clubService.isMember(clubId, userId)) {
                    send(session, err(msg, "不是俱乐部成员"));
                    return;
                }
                List<Map<String, Object>> list = new ArrayList<>();
                for (DzRoom r : roomManager.list()) {
                    if (r.getClubId() != clubId) continue;
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("roomId", r.getRoomId());
                    m.put("name", r.getName());
                    m.put("clubId", r.getClubId());
                    m.put("sb", r.getSb());
                    m.put("bb", r.getBb());
                    m.put("maxPlayers", r.getMaxPlayers());
                    m.put("settleTimeMins", r.getSettleTimeMins());
                    if (r.getRules() != null) {
                        m.put("rules", r.getRules().toMap());
                    }
                    int seated = 0;
                    // 已坐玩家快照(对齐扯旋 ClubTableItem 头像列)
                    List<Map<String, Object>> players = new ArrayList<>();
                    for (var p : r.getSeats()) {
                        if (p == null) continue;
                        seated++;
                        players.add(Map.of("userId", p.getUserId(),
                                "nickname", p.getNickname() == null ? "" : p.getNickname(),
                                "avatar", p.getAvatar() == null ? "" : p.getAvatar(),
                                "seat", p.getSeat()));
                    }
                    m.put("seated", seated);
                    m.put("players", players);
                    m.put("creatorUserId", r.getCreatorUserId());
                    m.put("stage", r.getStage().name());
                    list.add(m);
                }
                GameMessage res = GameMessage.create(MsgType.ROOM_LIST_RES, null, Map.of("rooms", list));
                res.setSequence(msg.getSequence());
                send(session, res);
            }
            case MsgType.CREATE_ROOM -> {
                // 停服维护(对齐扯旋总后台开关):禁止建房,不影响已在玩的
                if (cfg.getBool("maintenance_mode", false)) {
                    send(session, err(msg, "服务器维护更新中,暂时无法创建牌局,请稍后再来"));
                    return;
                }
                // 全量参数解析+校验在 RoomRules(对齐老德州建房参数)
                com.chexuan.dzpk.game.rules.RoomRules rules;
                try {
                    rules = com.chexuan.dzpk.game.rules.RoomRules.parse(data, nickname + "的牌局");
                } catch (IllegalArgumentException e) {
                    send(session, err(msg, e.getMessage()));
                    return;
                }
                // 结算时长/小盲须在后台配置档内(对齐扯旋 available_settle_time;档位也决定扣钻矩阵行列)
                if (!cfg.getLongList("room_settle_time_options", "30,45,60,90,120")
                        .contains((long) rules.getSettleTimeMins())) {
                    send(session, err(msg, "结算时长 " + rules.getSettleTimeMins() + " 分钟不在可选档内"));
                    return;
                }
                if (!cfg.getLongList("room_blind_options", "50,100,250,500,1000").contains(rules.getSb())) {
                    send(session, err(msg, "小盲 " + rules.getSb() + " 不在可选档内"));
                    return;
                }
                // 费率拖动条(对齐 Unity):俱乐部房 0~rakeMax,大厅房强制 0
                long rakeMax = cfg.getLong("room_rake_max", 5);
                if (rules.getClubId() <= 0) {
                    rules.setRakePercent(0);
                } else if (rules.getRakePercent() > rakeMax) {
                    send(session, err(msg, "费率最高 " + rakeMax + "%"));
                    return;
                }
                // 带入倍数区间上限(对齐 Unity 双把手 1~8)
                long inRateMax = cfg.getLong("room_in_rate_max", 8);
                if (rules.getInMaxRate() > inRateMax) {
                    send(session, err(msg, "带入倍数最高 " + inRateMax + " 倍"));
                    return;
                }
                // 俱乐部房:仅群主/管理员可建(对齐扯旋)
                if (rules.getClubId() > 0 && !clubService.canCreateRoom(rules.getClubId(), userId)) {
                    send(session, err(msg, "需要群主/管理员权限才能创建俱乐部牌局"));
                    return;
                }
                // 建房扣钻石(公用货币,主库 user.diamond;游客/机器人无主服账号跳过)
                long cost = 0;
                long createCost = cfg.getLong("create_room_diamond_cost", createRoomDiamondCost);
                if (createCost > 0 && diamondService.hasMainAccount(userId)) {
                    if (!diamondService.debit(userId, createCost, "create_room", "德州建房")) {
                        send(session, err(msg, "钻石不足,创建房间需要 " + createCost + " 钻石"));
                        return;
                    }
                    cost = createCost;
                }
                DzRoom room = roomManager.create(rules, userId);
                records.saveRoomCreated(room, cost);
                Map<String, Object> resData = new LinkedHashMap<>(rules.toMap());
                resData.put("roomId", room.getRoomId());
                resData.put("diamondCost", cost);
                resData.put("diamond", diamondService.balance(userId));
                GameMessage res = GameMessage.create(MsgType.CREATE_ROOM_RES, room.getRoomId(), resData);
                res.setSequence(msg.getSequence());
                send(session, res);
                log.info("创建房间: roomId={}, name={}, sb/bb={}/{}, 人数={}, settle={}min, rake={}%, " +
                                "ante={}, straddle={}, 保险={}, muck={}, 钻石={}",
                        room.getRoomId(), rules.getName(), rules.getSb(), rules.bb(), rules.getMaxPlayers(),
                        rules.getSettleTimeMins(), rules.getRakePercent(),
                        rules.getAnte(), rules.isStraddleOn(), rules.isInsuranceOn(), rules.isMuckOn(), cost);
            }
            // 观战不校验成员(对齐扯旋:任何人可进房观战,坐下才查成员)
            case MsgType.ENTER_ROOM -> gameService.enterRoom(roomId, userId, nickname);
            case MsgType.LEAVE_ROOM -> gameService.leaveRoom(roomId, userId);
            case MsgType.SIT_DOWN -> {
                if (cfg.getBool("maintenance_mode", false)) {
                    send(session, err(msg, "服务器维护更新中,暂时无法进入游戏,请稍后再来"));
                    return;
                }
                gameService.sitDown(roomId, userId, (int) lng(data, "seat", -1), clientIp(session),
                        (String) session.getAttributes().getOrDefault(ATTR_AVATAR, ""));
            }
            case MsgType.BUY_IN -> gameService.buyIn(roomId, userId, lng(data, "amount", 0));
            case MsgType.STAND_UP -> gameService.standUp(roomId, userId,
                    Boolean.TRUE.equals(data.get("confirmFine")) || lng(data, "confirmFine", 0) == 1);
            case MsgType.ACTION -> gameService.action(roomId, userId, str(data, "act"), lng(data, "amount", 0));
            case MsgType.INSURANCE_BUY -> gameService.insuranceBuy(roomId, userId, lng(data, "amount", 0));
            case MsgType.SNAPSHOT -> gameService.snapshotTo(roomId, userId);
            case MsgType.SEAT_RESERVE_LEAVE -> gameService.seatReserveLeave(roomId, userId);
            case MsgType.SEAT_RESERVE_RESUME -> gameService.seatReserveResume(roomId, userId);
            case MsgType.NEXT_CARD -> gameService.nextCard(roomId, userId);
            case MsgType.SHOW_CARDS -> gameService.showCards(roomId, userId, (int) lng(data, "mode", 3));
            case MsgType.HAND_REVIEW -> gameService.handReview(roomId, userId, lng(data, "handNo", -1),
                    lng(data, "forceShow", 0) == 1, msg.getSequence());
            case MsgType.REALTIME_STATS -> gameService.realtimeStats(roomId, userId, msg.getSequence());
            case MsgType.DISMISS_ROOM -> gameService.dismissRoom(roomId, userId);
            case MsgType.GIFT_LIST -> reply(session, msg, MsgType.GIFT_LIST_RES,
                    Map.of("gifts", giftService.listEnabled()));
            // 建房参数可选档(后台可配,对齐扯旋 available_settle_time/available_base_scores)
            case MsgType.ROOM_OPTIONS -> reply(session, msg, MsgType.ROOM_OPTIONS_RES, Map.of(
                    "settleTimes", cfg.getLongList("room_settle_time_options", "30,45,60,90,120"),
                    "blinds", cfg.getLongList("room_blind_options", "50,100,250,500,1000"),
                    "opTimes", cfg.getLongList("room_op_time_options", "10,15,20,30"),
                    "maxRates", cfg.getLongList("room_max_rate_options", "2,4,10"),
                    "minTimes", cfg.getLongList("room_min_time_options", "0,30,60"),
                    "rakePercents", cfg.getLongList("room_rake_percent_options", "0,3,5,10"),
                    // 对齐 Unity:费率拖动条 0~rakeMax(大厅房强制0);带入倍数双把手区间 1~inRateMax
                    "rakeMax", cfg.getLong("room_rake_max", 5),
                    "inRateMax", cfg.getLong("room_in_rate_max", 8)));
            case MsgType.GIFT_SEND -> giftService.sendRoomGift(roomId, userId,
                    lng(data, "giftId", 0), lng(data, "toUserId", 0));
            case MsgType.MY_RECORDS -> {
                int limit = (int) lng(data, "limit", 20);
                long recClubId = lng(data, "clubId", 0); // >0 = 俱乐部维度战绩(对齐扯旋 gameSummary)
                GameMessage res = GameMessage.create(MsgType.MY_RECORDS_RES, null, Map.of(
                        "records", records.myRecords(userId, limit, recClubId),
                        "stats", records.myStats(userId, recClubId)));
                res.setSequence(msg.getSequence());
                send(session, res);
            }
            // ==================== 俱乐部 ====================
            case MsgType.CLUB_CREATE -> reply(session, msg, MsgType.CLUB_CREATE_RES,
                    clubService.createClub(userId, nickname,
                            str(data, "name"), str(data, "remark"), str(data, "avatar")));
            case MsgType.CLUB_LIST -> reply(session, msg, MsgType.CLUB_LIST_RES,
                    Map.of("clubs", clubService.myClubs(userId)));
            case MsgType.CLUB_APPLY -> reply(session, msg, MsgType.CLUB_APPLY_RES,
                    clubService.apply(userId, nickname, lng(data, "code", 0)));
            case MsgType.CLUB_APPLY_LIST -> {
                // clubId=0:聚合我管理的全部俱乐部待审(顶栏消息弹框,对齐扯旋 getAllMyClubsJoinRequests)
                long applyClubId = lng(data, "clubId", 0);
                reply(session, msg, MsgType.CLUB_APPLY_LIST_RES, Map.of("requests",
                        applyClubId > 0 ? clubService.applyList(applyClubId, userId)
                                        : clubService.applyListAll(userId)));
            }
            case MsgType.CLUB_REVIEW -> {
                long clubId = lng(data, "clubId", 0);
                boolean approve = Boolean.TRUE.equals(data.get("approve")) || lng(data, "approve", 0) == 1;
                long applicant = clubService.review(clubId, userId, lng(data, "requestId", 0), approve);
                reply(session, msg, MsgType.CLUB_REVIEW_RES,
                        Map.of("requestId", lng(data, "requestId", 0), "approve", approve, "userId", applicant));
                // 推送审批结果给申请人(在线才收得到)
                notifyUser(applicant, clubId, approve);
            }
            case MsgType.CLUB_MEMBERS -> reply(session, msg, MsgType.CLUB_MEMBERS_RES,
                    Map.of("members", clubService.members(lng(data, "clubId", 0), userId)));
            case MsgType.CLUB_SET_ROLE -> {
                clubService.setRole(lng(data, "clubId", 0), userId, lng(data, "userId", 0),
                        (int) lng(data, "role", 1), (int) lng(data, "partnerRate", 0));
                reply(session, msg, MsgType.CLUB_OP_RES, Map.of("op", "setRole"));
            }
            case MsgType.CLUB_KICK -> {
                clubService.kick(lng(data, "clubId", 0), userId, lng(data, "userId", 0));
                reply(session, msg, MsgType.CLUB_OP_RES, Map.of("op", "kick"));
            }
            case MsgType.CLUB_QUIT -> {
                clubService.quit(lng(data, "clubId", 0), userId);
                reply(session, msg, MsgType.CLUB_OP_RES, Map.of("op", "quit"));
            }
            case MsgType.CLUB_DISSOLVE -> {
                clubService.dissolve(lng(data, "clubId", 0), userId);
                reply(session, msg, MsgType.CLUB_OP_RES, Map.of("op", "dissolve"));
            }
            // 俱乐部积分(每俱乐部独立一本账,带入货币):增发/核销/上分/下分/赠送
            case MsgType.CLUB_SCORE_OP -> {
                long clubId = lng(data, "clubId", 0);
                long amount = lng(data, "amount", 0);
                long target = lng(data, "userId", 0);
                Map<String, Object> r = switch (String.valueOf(str(data, "op"))) {
                    case "ownerAdd" -> clubService.ownerAddScore(clubId, userId, amount);
                    case "ownerBurn" -> clubService.ownerBurnScore(clubId, userId, amount);
                    case "distribute" -> clubService.distributeScore(clubId, userId, target, amount);
                    case "collect" -> clubService.collectScore(clubId, userId, target, amount);
                    case "transfer" -> clubService.transferScore(clubId, userId, target, amount);
                    default -> throw new DzClubService.ClubException("未知积分操作");
                };
                reply(session, msg, MsgType.CLUB_OP_RES, r);
            }
            // GPS 上报(防火牌):无应答,内存存储
            case MsgType.GPS_REPORT -> gpsService.update(userId,
                    dbl(data, "lat"), dbl(data, "lng"));
            case MsgType.CLUB_SCORE_LOGS -> reply(session, msg, MsgType.CLUB_SCORE_LOGS_RES,
                    Map.of("logs", clubService.scoreLogs(lng(data, "clubId", 0), userId,
                            lng(data, "userId", 0), (int) lng(data, "limit", 50))));
            case MsgType.CLUB_UPDATE -> reply(session, msg, MsgType.CLUB_UPDATE_RES,
                    clubService.updateClub(lng(data, "clubId", 0), userId,
                            str(data, "name"), str(data, "remark"), str(data, "avatar"), str(data, "notice")));
            default -> send(session, err(msg, "未知命令 " + msg.getType()));
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Long userId = (Long) session.getAttributes().get(ATTR_USER_ID);
        if (userId != null) {
            registry.unbind(userId, session);
            // 掉线不踢座(对齐扯旋):标离线+vacationPending,代弃后自动进放假;重连进房自动回线
            gameService.onDisconnect(userId);
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

    private Double dbl(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v instanceof Number n) return n.doubleValue();
        if (v instanceof String s) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
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

    /** 带 sequence 的成功应答 */
    private void reply(WebSocketSession session, GameMessage req, int type, Map<String, Object> data) {
        GameMessage res = GameMessage.create(type, null, data);
        res.setSequence(req.getSequence());
        send(session, res);
    }

    /** 审批结果推送给申请人 */
    private void notifyUser(long userId, long clubId, boolean approve) {
        try {
            registry.toUser(userId, GameMessage.create(MsgType.CLUB_NOTIFY, null,
                    Map.of("clubId", clubId, "clubName", clubService.clubName(clubId), "approve", approve)));
        } catch (Exception e) {
            log.warn("审批通知失败: userId={}, {}", userId, e.getMessage());
        }
    }

    /** 客户端 IP(AccessRule 同 IP 限制用;有反代时优先握手头 X-Forwarded-For) */
    private String clientIp(WebSocketSession session) {
        try {
            String fwd = session.getHandshakeHeaders().getFirst("X-Forwarded-For");
            if (fwd != null && !fwd.isBlank()) {
                return fwd.split(",")[0].trim();
            }
            return session.getRemoteAddress() != null
                    ? session.getRemoteAddress().getAddress().getHostAddress() : null;
        } catch (Exception e) {
            return null;
        }
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
