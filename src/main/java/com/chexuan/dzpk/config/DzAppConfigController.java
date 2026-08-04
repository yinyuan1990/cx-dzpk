package com.chexuan.dzpk.config;

import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 客户端公开配置(免登录):目前只有分享地址,顶栏分享按钮点击时实时拉取,
 * 管理台「参数配置」改 share_app_url 即刻生效。
 */
@RestController
@RequestMapping("/api/app")
@CrossOrigin
public class DzAppConfigController {

    private final DzConfigService cfg;

    public DzAppConfigController(DzConfigService cfg) {
        this.cfg = cfg;
    }

    @GetMapping("/config")
    public Map<String, Object> config() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("code", 0);
        out.put("shareUrl", cfg.getStr("share_app_url", ""));
        return out;
    }
}
