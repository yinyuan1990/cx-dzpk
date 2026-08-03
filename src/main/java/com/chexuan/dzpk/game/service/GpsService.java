package com.chexuan.dzpk.game.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * GPS 防火牌(对齐扯旋 GpsService):
 *   前端定时上报坐标(GPS_REPORT 432),坐下时校验与桌上每个玩家的距离,
 *   开了 gpsLimitOn 的桌,距离过近或数据缺失/过期一律拒坐。
 * 内存存储,重启清零(前端重连后会重新上报)。
 */
@Slf4j
@Service
public class GpsService {

    public record Loc(double lat, double lng, long time) {
    }

    private final Map<Long, Loc> userGps = new ConcurrentHashMap<>();

    public void update(long userId, Double lat, Double lng) {
        if (lat == null || lng == null) return;
        if (lat < -90 || lat > 90 || lng < -180 || lng > 180) return;
        userGps.put(userId, new Loc(lat, lng, System.currentTimeMillis()));
    }

    public void remove(long userId) {
        userGps.remove(userId);
    }

    /** Haversine 米距 */
    public static double distanceMeters(double lat1, double lng1, double lat2, double lng2) {
        final double R = 6371000.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    /** 校验 A 与 B 距离 ≥ minMeters 且双方数据新鲜;可坐返回 null,否则返回拒坐文案 */
    public String checkPair(long userIdA, long userIdB, double minMeters, long maxAgeMs) {
        Loc a = userGps.get(userIdA);
        Loc b = userGps.get(userIdB);
        long now = System.currentTimeMillis();
        if (a == null) return "你的 GPS 未上报,请开启定位权限后重试";
        if (b == null) return "同桌玩家 GPS 未上报,无法判定距离";
        if (now - a.time() > maxAgeMs) return "你的 GPS 数据已过期,请重新定位";
        if (now - b.time() > maxAgeMs) return "同桌玩家 GPS 数据已过期,无法判定距离";
        double dist = distanceMeters(a.lat(), a.lng(), b.lat(), b.lng());
        if (dist < minMeters) {
            return "GPS 防火牌:与同桌玩家距离约 " + (long) dist + " 米,需大于 " + (long) minMeters + " 米";
        }
        return null;
    }
}
