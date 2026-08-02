package com.chexuan.dzpk.ws;

import com.chexuan.dzpk.game.model.DzRoom;
import com.chexuan.dzpk.game.service.DzRoomManager;
import com.chexuan.dzpk.game.service.GameBroadcaster;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话注册表 + 广播实现。
 * userId → 会话(ConcurrentWebSocketSessionDecorator 保证并发写安全)。
 */
@Slf4j
@Component
public class WsSessionRegistry implements GameBroadcaster {

    private final Map<Long, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final DzRoomManager roomManager;
    private final ObjectMapper objectMapper;

    public WsSessionRegistry(DzRoomManager roomManager, ObjectMapper objectMapper) {
        this.roomManager = roomManager;
        this.objectMapper = objectMapper;
    }

    public void bind(long userId, WebSocketSession session) {
        WebSocketSession wrapped = new ConcurrentWebSocketSessionDecorator(session, 5000, 512 * 1024);
        WebSocketSession old = sessions.put(userId, wrapped);
        if (old != null && old.isOpen() && !old.getId().equals(session.getId())) {
            try {
                old.close();
            } catch (Exception ignored) {
            }
        }
    }

    public void unbind(long userId, WebSocketSession session) {
        sessions.computeIfPresent(userId, (k, v) ->
                v.getId().equals(session.getId()) ? null : v);
    }

    /** 当前在线连接数(管理后台监控用) */
    public int onlineCount() {
        int n = 0;
        for (WebSocketSession s : sessions.values()) {
            if (s.isOpen()) n++;
        }
        return n;
    }

    @Override
    public void toUser(long userId, GameMessage msg) {
        WebSocketSession session = sessions.get(userId);
        if (session == null || !session.isOpen()) return;
        try {
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(msg)));
        } catch (Exception e) {
            log.warn("发送失败: userId={}, type={}, {}", userId, msg.getType(), e.getMessage());
        }
    }

    @Override
    public void toRoom(long roomId, GameMessage msg) {
        DzRoom room = roomManager.get(roomId);
        if (room == null) return;
        String json;
        try {
            json = objectMapper.writeValueAsString(msg);
        } catch (Exception e) {
            log.error("序列化失败: type={}", msg.getType(), e);
            return;
        }
        TextMessage text = new TextMessage(json);
        for (Long userId : room.getMembers().keySet()) {
            WebSocketSession session = sessions.get(userId);
            if (session == null || !session.isOpen()) continue;
            try {
                session.sendMessage(text);
            } catch (Exception e) {
                log.warn("房间广播失败: userId={}, {}", userId, e.getMessage());
            }
        }
    }
}
