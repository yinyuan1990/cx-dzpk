package com.chexuan.dzpk.ws;

/**
 * 德州子游戏命令号 — 独立 4xx 段,与扯旋主服命令号空间隔离。
 * C→S 40x/41x,S→C 45x/46x,错误 499。
 */
public final class MsgType {

    private MsgType() {
    }

    // ==================== C → S ====================
    public static final int LOGIN = 401;        // {token} 或 {guest:"昵称"}
    public static final int ROOM_LIST = 402;
    public static final int CREATE_ROOM = 403;  // {name,sb,bb,maxPlayers,settleTimeMins,rakePercent}
    public static final int ENTER_ROOM = 404;   // {roomId}
    public static final int LEAVE_ROOM = 405;
    public static final int SIT_DOWN = 406;     // {seat}
    public static final int BUY_IN = 407;       // {amount}
    public static final int STAND_UP = 408;
    public static final int ACTION = 409;       // {act:"fold|check|call|raise|allin", amount(raise-to)}
    public static final int SNAPSHOT = 410;
    public static final int MY_RECORDS = 411;   // 我的战绩 {limit?}

    // -------- 俱乐部(42x) --------
    public static final int CLUB_CREATE = 420;      // {name, notice?}
    public static final int CLUB_LIST = 421;        // 我的俱乐部列表
    public static final int CLUB_APPLY = 422;       // {code} 俱乐部号或邀请码
    public static final int CLUB_APPLY_LIST = 423;  // {clubId} 待审批(群主/管理员)
    public static final int CLUB_REVIEW = 424;      // {clubId, requestId, approve}
    public static final int CLUB_MEMBERS = 425;     // {clubId}
    public static final int CLUB_SET_ROLE = 426;    // {clubId, userId, role, partnerRate?}
    public static final int CLUB_KICK = 427;        // {clubId, userId}
    public static final int CLUB_QUIT = 428;        // {clubId}
    public static final int CLUB_DISSOLVE = 429;    // {clubId}

    // ==================== S → C ====================
    public static final int LOGIN_RES = 451;
    public static final int ROOM_LIST_RES = 452;
    public static final int CREATE_ROOM_RES = 453;
    public static final int ENTER_ROOM_RES = 454;   // 含全量快照
    public static final int PLAYER_ENTER = 455;
    public static final int PLAYER_SIT = 456;
    public static final int BUY_IN_RES = 457;
    public static final int HAND_START = 458;
    public static final int HOLE_CARDS = 459;       // 私发
    public static final int TURN = 460;             // 轮到谁行动
    public static final int ACTION_BC = 461;
    public static final int DEAL = 462;             // 发公共牌
    public static final int SHOWDOWN = 463;
    public static final int SETTLE = 464;           // 一手结算
    public static final int PLAYER_STAND = 465;
    public static final int SNAPSHOT_RES = 466;
    public static final int PLAYER_LEAVE = 467;
    public static final int PERIOD_SETTLE = 468;    // 周期结算面板(补带入等待)
    public static final int ROOM_STATE = 469;       // WAITING/FINISHED 等房态变化
    public static final int STAND_UP_RES = 470;     // 站起回执 {pending:牌局中申请,本手结束生效}
    public static final int MY_RECORDS_RES = 471;   // {records:[...], stats:{...}}

    // -------- 俱乐部(48x) --------
    public static final int CLUB_CREATE_RES = 480;
    public static final int CLUB_LIST_RES = 481;
    public static final int CLUB_APPLY_RES = 482;
    public static final int CLUB_APPLY_LIST_RES = 483;
    public static final int CLUB_REVIEW_RES = 484;
    public static final int CLUB_MEMBERS_RES = 485;
    public static final int CLUB_OP_RES = 486;      // 设角色/踢人/退出/解散 通用回执 {op}
    public static final int CLUB_NOTIFY = 487;      // 推送:审批结果 {clubId, clubName, approve}

    public static final int ERROR = 499;
}
