package com.chexuan.dzpk.game.service;

import com.chexuan.dzpk.game.model.DzRoom;
import com.chexuan.dzpk.game.rules.RoomRules;
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

    /** 完整规则建房(正式入口) */
    public DzRoom create(RoomRules rules, long creatorUserId) {
        long roomId;
        do {
            roomId = ThreadLocalRandom.current().nextLong(100000, 1000000);
        } while (rooms.containsKey(roomId));
        return build(roomId, rules, creatorUserId);
    }

    /** 重启恢复(保留原房号;已存在则跳过) */
    public DzRoom restore(long roomId, RoomRules rules, long creatorUserId) {
        DzRoom exist = rooms.get(roomId);
        return exist != null ? exist : build(roomId, rules, creatorUserId);
    }

    private DzRoom build(long roomId, RoomRules rules, long creatorUserId) {
        DzRoom room = new DzRoom(rules.getMaxPlayers());
        room.setRoomId(roomId);
        room.setCreatorUserId(creatorUserId);
        room.setRules(rules);
        // 镜像字段:老代码继续读这些
        room.setName(rules.getName());
        room.setClubId(rules.getClubId());
        room.setSb(rules.getSb());
        room.setBb(rules.bb());
        room.setMinBuyin(rules.minBuyin());
        room.setMaxBuyin(rules.maxBuyin());
        room.setSettleTimeMins(rules.getSettleTimeMins());
        room.setRakePercent(rules.getRakePercent());
        rooms.put(roomId, room);
        return room;
    }

    /** 兼容旧签名(单测用):按老默认带入 40~400BB */
    public DzRoom create(String name, long creatorUserId, long sb, long bb,
                         int maxPlayers, int settleTimeMins, int rakePercent) {
        return create(name, creatorUserId, sb, bb, maxPlayers, settleTimeMins, rakePercent, 0);
    }

    /** 兼容旧签名(单测用) */
    public DzRoom create(String name, long creatorUserId, long sb, long bb,
                         int maxPlayers, int settleTimeMins, int rakePercent, long clubId) {
        RoomRules rules = RoomRules.legacy(name, sb, bb, maxPlayers, settleTimeMins, rakePercent);
        rules.setClubId(clubId);
        DzRoom room = create(rules, creatorUserId);
        // 老测试的带入范围沿用旧默认(bb*40 ~ bb*400)
        room.setMinBuyin(bb * 40);
        room.setMaxBuyin(bb * 400);
        rules.setInChip(bb * 40);
        rules.setInMinRate(1);
        rules.setInMaxRate(10);
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
