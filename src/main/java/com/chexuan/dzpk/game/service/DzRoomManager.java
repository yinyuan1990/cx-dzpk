package com.chexuan.dzpk.game.service;

import com.chexuan.dzpk.game.model.DzRoom;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 房间注册表 — 6 位数字房号,内存态
 */
@Service
public class DzRoomManager {

    private final Map<Long, DzRoom> rooms = new ConcurrentHashMap<>();

    public DzRoom create(String name, long creatorUserId, long sb, long bb,
                         int maxPlayers, int settleTimeMins, int rakePercent) {
        long roomId;
        do {
            roomId = ThreadLocalRandom.current().nextLong(100000, 1000000);
        } while (rooms.containsKey(roomId));

        DzRoom room = new DzRoom(maxPlayers);
        room.setRoomId(roomId);
        room.setName(name);
        room.setCreatorUserId(creatorUserId);
        room.setSb(sb);
        room.setBb(bb);
        room.setMinBuyin(bb * 40);
        room.setMaxBuyin(bb * 400);
        room.setSettleTimeMins(settleTimeMins);
        room.setRakePercent(rakePercent);
        rooms.put(roomId, room);
        return room;
    }

    public DzRoom get(long roomId) {
        return rooms.get(roomId);
    }

    public List<DzRoom> list() {
        return new ArrayList<>(rooms.values());
    }

    public void remove(long roomId) {
        rooms.remove(roomId);
    }
}
