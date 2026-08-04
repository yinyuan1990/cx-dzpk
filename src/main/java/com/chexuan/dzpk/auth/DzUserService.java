package com.chexuan.dzpk.auth;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 德州独立账号(dz_user):与扯旋主服完全独立,不再代理主服登录;
 * 钻石也独立记在本表 diamond 列(DiamondService 指向 dz_user)。
 * 密码 SHA-256(salt+password) 存储;账号 id 即游戏 userId(< 8亿,机器人/游客段之下)。
 */
@Slf4j
@Service
public class DzUserService {

    public static class AuthException extends RuntimeException {
        public AuthException(String msg) {
            super(msg);
        }
    }

    private final JdbcTemplate jdbc;
    private final com.chexuan.dzpk.config.DzConfigService cfg;
    private final SecureRandom random = new SecureRandom();

    public DzUserService(JdbcTemplate jdbc, com.chexuan.dzpk.config.DzConfigService cfg) {
        this.jdbc = jdbc;
        this.cfg = cfg;
    }

    /**
     * 注册 — 字段/校验对标扯旋 RegisterRequest:
     *   phone ^1[3-9]\d{9}$ | username 昵称(禁纯数字,宽度≤register_nickname_max_length×2)
     *   avatar 必填 | password 字母+数字 6~20 位 | confirmPassword 须一致
     *   inviteCode 可选 | registerDevice 1=iOS 2=Android 3=Web
     * 成功自动生成 6 位唯一编号(numberId),返回 profile。
     */
    public Map<String, Object> register(String phone, String username, String avatar,
                                        String password, String confirmPassword,
                                        String inviteCode, int registerDevice) {
        phone = normPhone(phone);
        if (password == null || password.isBlank()) throw new AuthException("密码不能为空");
        if (!password.matches("^(?=.*[a-zA-Z])(?=.*\\d)[a-zA-Z\\d]{6,20}$")) {
            throw new AuthException("密码必须包含字母和数字,长度6-20位");
        }
        if (confirmPassword == null || confirmPassword.isBlank()) throw new AuthException("确认密码不能为空");
        if (!password.equals(confirmPassword)) throw new AuthException("两次密码输入不一致");
        String nickname = validNickname(username);
        if (avatar == null || avatar.isBlank()) throw new AuthException("头像不能为空");
        if (avatar.length() > 255) throw new AuthException("头像地址过长");

        Integer exists = jdbc.queryForObject("SELECT COUNT(*) FROM dz_user WHERE phone = ?", Integer.class, phone);
        if (exists != null && exists > 0) throw new AuthException("该手机号已注册");

        String salt = HexFormat.of().formatHex(randomBytes(8));
        long initDiamond = cfg != null ? cfg.getLong("register_init_diamond", 0) : 0;
        jdbc.update("INSERT INTO dz_user (phone, number_id, password_hash, salt, nickname, avatar, " +
                        "invite_code, register_device, diamond, state, created_at) VALUES (?,?,?,?,?,?,?,?,?,1,?)",
                phone, uniqueNumberId(), hash(salt, password), salt, nickname, avatar.trim(),
                inviteCode == null ? "" : inviteCode.trim(), registerDevice, initDiamond,
                new Timestamp(System.currentTimeMillis()));
        Map<String, Object> u = byPhone(phone);
        log.info("注册: userId={}, numberId={}, phone={}, nickname={}, device={}, 赠钻={}",
                u.get("userId"), u.get("numberId"), phone, nickname, registerDevice, initDiamond);
        return u;
    }

    /** 昵称校验(对标扯旋 NicknameValidator):必填、禁纯数字、显示宽度 ≤ 配置汉字数×2 */
    private String validNickname(String username) {
        if (username == null || username.isBlank()) throw new AuthException("昵称不能为空");
        String nickname = username.trim();
        if (nickname.chars().allMatch(Character::isDigit)) throw new AuthException("昵称不能是纯数字");
        int maxLen = cfg != null ? cfg.getInt("register_nickname_max_length", 4) : 4;
        if (displayWidth(nickname) > maxLen * 2) {
            throw new AuthException("昵称最长 " + maxLen + " 个汉字(或等宽字符)");
        }
        return nickname;
    }

    /** 6 位唯一编号(对标扯旋 numberId) */
    private String uniqueNumberId() {
        for (int i = 0; i < 20; i++) {
            String no = String.valueOf(100000 + random.nextInt(900000));
            Integer c = jdbc.queryForObject("SELECT COUNT(*) FROM dz_user WHERE number_id = ?", Integer.class, no);
            if (c == null || c == 0) return no;
        }
        throw new AuthException("编号分配失败,请重试");
    }

    /** 显示宽度:CJK 等全角算 2,半角算 1 */
    private static int displayWidth(String s) {
        int w = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            w += cp > 0xFF ? 2 : 1;
            i += Character.charCount(cp);
        }
        return w;
    }

    /** 登录:校验密码,返回 profile;失败抛 AuthException */
    public Map<String, Object> login(String phone, String password) {
        phone = normPhone(phone);
        Map<String, Object> row = rawByPhone(phone);
        if (row == null) throw new AuthException("账号不存在,请先注册");
        if (((Number) col(row, "state")).intValue() != 1) throw new AuthException("账号已被封禁");
        if (!hash((String) col(row, "salt"), password == null ? "" : password).equals(col(row, "password_hash"))) {
            throw new AuthException("密码错误");
        }
        jdbc.update("UPDATE dz_user SET last_login_at = ? WHERE id = ?",
                new Timestamp(System.currentTimeMillis()), col(row, "id"));
        return toProfile(row);
    }

    /** userId → profile;不存在/封禁返回 null(WS 登录用真实库昵称/头像) */
    public Map<String, Object> profile(long userId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM dz_user WHERE id = ? AND state = 1", userId);
        return rows.isEmpty() ? null : toProfile(rows.get(0));
    }

    private Map<String, Object> byPhone(String phone) {
        Map<String, Object> row = rawByPhone(phone);
        return row == null ? null : toProfile(row);
    }

    private Map<String, Object> rawByPhone(String phone) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM dz_user WHERE phone = ?", phone);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static Map<String, Object> toProfile(Map<String, Object> row) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("userId", ((Number) col(row, "id")).longValue());
        m.put("numberId", col(row, "number_id") == null ? "" : col(row, "number_id"));
        m.put("phone", col(row, "phone"));
        m.put("nickname", col(row, "nickname"));
        m.put("avatar", col(row, "avatar") == null ? "" : col(row, "avatar"));
        m.put("diamond", ((Number) col(row, "diamond")).longValue());
        return m;
    }

    /** 取列值(H2 返回大写列名/MySQL 小写,两头兼容) */
    private static Object col(Map<String, Object> row, String key) {
        Object v = row.get(key);
        return v != null ? v : row.get(key.toUpperCase());
    }

    /** 手机号格式对标扯旋:^1[3-9]\d{9}$ */
    private static String normPhone(String phone) {
        if (phone == null || phone.isBlank()) throw new AuthException("手机号不能为空");
        phone = phone.trim();
        if (!phone.matches("^1[3-9]\\d{9}$")) throw new AuthException("手机号格式不正确");
        return phone;
    }

    private byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        random.nextBytes(b);
        return b;
    }

    private static String hash(String salt, String password) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest((salt + password).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
