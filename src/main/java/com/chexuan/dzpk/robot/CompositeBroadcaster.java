package com.chexuan.dzpk.robot;

import com.chexuan.dzpk.game.service.GameBroadcaster;
import com.chexuan.dzpk.ws.GameMessage;
import com.chexuan.dzpk.ws.WsSessionRegistry;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * 广播复合出口 — 引擎的广播同时发给真人(WS 会话)和机器人(RobotService)。
 * 机器人与真人共用同一套消息协议,不给引擎开任何后门。
 */
@Primary
@Component
public class CompositeBroadcaster implements GameBroadcaster {

    private final WsSessionRegistry registry;
    private final RobotService robotService;

    public CompositeBroadcaster(WsSessionRegistry registry, RobotService robotService) {
        this.registry = registry;
        this.robotService = robotService;
    }

    @Override
    public void toUser(long userId, GameMessage msg) {
        if (robotService.isRobot(userId)) {
            robotService.onUserMessage(userId, msg);
        } else {
            registry.toUser(userId, msg);
        }
    }

    @Override
    public void toRoom(long roomId, GameMessage msg) {
        registry.toRoom(roomId, msg);
        robotService.onRoomMessage(roomId, msg);
    }
}
