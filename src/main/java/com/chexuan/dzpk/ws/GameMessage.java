package com.chexuan.dzpk.ws;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 游戏消息信封 — 与扯旋主服 GameMessage 同构:
 * {type, roomId, sequence, data, timestamp}
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GameMessage {

    private Integer type;
    private Long roomId;
    /** 请求序列号(前端自增,响应原样返回) */
    private Long sequence;
    private Object data;
    private Long timestamp;

    public static GameMessage create(Integer type, Long roomId, Object data) {
        return GameMessage.builder()
                .type(type)
                .roomId(roomId)
                .data(data)
                .timestamp(System.currentTimeMillis())
                .build();
    }
}
