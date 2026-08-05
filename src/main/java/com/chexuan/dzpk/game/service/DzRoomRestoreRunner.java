package com.chexuan.dzpk.game.service;

import com.chexuan.dzpk.db.DzRecordStore;
import com.chexuan.dzpk.game.rules.RoomRules;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 重启恢复房间 — 房间是内存态,原来重启就全丢(用户体感"房间消失")。
 * dz_room 表存了建房全量参数(rules_json)+ closed_at 关闭标记:
 * 启动时把「未关闭的俱乐部房」按原房号原参数重建成空桌(WAITING,等人坐下),
 * 对齐扯旋"俱乐部空桌长期存活"的语义;大厅散房保持"没人就销毁"不恢复。
 */
@Slf4j
@Component
public class DzRoomRestoreRunner {

    private final DzRecordStore records;
    private final DzRoomManager roomManager;
    private final ObjectMapper mapper = new ObjectMapper();

    public DzRoomRestoreRunner(DzRecordStore records, DzRoomManager roomManager) {
        this.records = records;
        this.roomManager = roomManager;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void restore() {
        int n = 0;
        for (Map<String, Object> row : records.openRooms()) {
            try {
                long roomId = ((Number) col(row, "room_id")).longValue();
                long creator = ((Number) col(row, "creator_user_id")).longValue();
                String json = String.valueOf(col(row, "rules_json"));
                if (json == null || json.isBlank() || "null".equals(json)) continue;
                @SuppressWarnings("unchecked")
                Map<String, Object> data = mapper.readValue(json, Map.class);
                RoomRules rules = RoomRules.parse(data, "牌局" + roomId);
                if (rules.getClubId() <= 0) continue; // 大厅散房不恢复(没人就销毁的语义)
                roomManager.restore(roomId, rules, creator);
                n++;
            } catch (Exception e) {
                log.warn("房间恢复失败(跳过): {}", row, e);
            }
        }
        if (n > 0) log.info("重启恢复俱乐部房间: {} 个(空桌等待,原房号原参数)", n);
    }

    private static Object col(Map<String, Object> row, String key) {
        Object v = row.get(key);
        return v != null ? v : row.get(key.toUpperCase());
    }
}
