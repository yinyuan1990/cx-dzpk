package com.chexuan.dzpk.game.service;

import com.chexuan.dzpk.ws.GameMessage;

/**
 * 广播出口 — 引擎只依赖此接口,由 WS 会话层实现,解开引擎与 WebSocket 的循环依赖。
 */
public interface GameBroadcaster {

    /** 发给房间内所有人 */
    void toRoom(long roomId, GameMessage msg);

    /** 私发给某个用户 */
    void toUser(long userId, GameMessage msg);
}
