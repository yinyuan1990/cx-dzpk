package com.chexuan.dzpk;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 德州扑克子游戏服务 — 独立微服务,不依赖扯旋主服进程。
 *
 * 玩法核心移植自老德州(hsdz game/room):
 *   - 牌型评估/比较: BiPai(zuidapai/bipai2) 原样移植
 *   - 下注轮/边池/结算: 按老服规则实现
 * 通信: WebSocket + GameMessage JSON 信封(与扯旋同构,命令号独立 4xx 段)
 */
@SpringBootApplication
public class DzpkApplication {
    public static void main(String[] args) {
        SpringApplication.run(DzpkApplication.class, args);
    }
}
