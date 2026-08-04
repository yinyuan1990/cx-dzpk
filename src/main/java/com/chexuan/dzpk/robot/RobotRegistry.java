package com.chexuan.dzpk.robot;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 机器人账号注册表(对齐扯旋 RobotStressManager 的 robotIds 缓存):
 * dz_user.is_robot=1 的真实账号,启动时全量加载,生成时增量加。
 * 只做"这个 userId 是不是机器人"的内存判断,供引擎(GPS 豁免)/广播路由/机器人驱动用。
 */
@Slf4j
@Service
public class RobotRegistry {

    private final JdbcTemplate jdbc;
    private final Set<Long> robotIds = ConcurrentHashMap.newKeySet();

    public RobotRegistry(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    public void load() {
        try {
            robotIds.addAll(jdbc.queryForList("SELECT id FROM dz_user WHERE is_robot = 1", Long.class));
            log.info("机器人账号加载: {} 个", robotIds.size());
        } catch (Exception e) {
            log.warn("机器人账号加载失败(表未就绪?): {}", e.getMessage());
        }
    }

    public boolean isRobot(long userId) {
        return robotIds.contains(userId);
    }

    public void add(long userId) {
        robotIds.add(userId);
    }

    public int count() {
        return robotIds.size();
    }
}
