package com.chexuan.dzpk.upload;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 上传预签名接口(对标扯旋聊天直传模型):
 * POST /api/upload/prepare {type, fileName, fileSize}
 *   → {code:0, presignedUrl, accessUrl}
 * 客户端本地压缩后 PUT presignedUrl,把 accessUrl 存业务字段(如注册 avatar)。
 * 注册头像在登录前上传,故本接口匿名可用;靠类型/扩展名/大小校验 + 30 分钟时效防滥用。
 */
@Slf4j
@RestController
@RequestMapping("/api/upload")
@CrossOrigin
public class DzUploadController {

    private final DzMinioService minio;

    public DzUploadController(DzMinioService minio) {
        this.minio = minio;
    }

    @PostMapping("/prepare")
    public Map<String, Object> prepare(@RequestBody Map<String, Object> body) {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            String type = str(body, "type");
            String fileName = str(body, "fileName");
            long fileSize = body.get("fileSize") instanceof Number n ? n.longValue() : -1;
            DzMinioService.PresignedUpload r = minio.prepareUpload(type, fileName, fileSize);
            out.put("code", 0);
            out.put("presignedUrl", r.presignedUrl);
            out.put("accessUrl", r.accessUrl);
            return out;
        } catch (IllegalArgumentException | IllegalStateException e) {
            out.put("code", 1);
            out.put("msg", e.getMessage());
            return out;
        } catch (Exception e) {
            log.error("上传预签名异常", e);
            out.put("code", 1);
            out.put("msg", "上传服务异常");
            return out;
        }
    }

    private static String str(Map<String, Object> body, String key) {
        Object v = body.get(key);
        return v == null ? null : String.valueOf(v);
    }
}
