package com.chexuan.dzpk.upload;

import io.minio.BucketExistsArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.SetBucketPolicyArgs;
import io.minio.http.Method;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * MinIO 对象存储 — 对标扯旋 MinioService(预签名直传模型)。
 *
 * 复用服务器上扯旋栈的 MinIO 实例(chexuan-minio,内网 minio:9000),但用独立桶 cx-dzpk;
 * 外网走 nginx 域名(https://cxchat.cocoaihj.com),预签名 URL 基于外网端点签发,
 * 客户端(浏览器)压缩后直接 PUT,后端不过图片流量。
 * 当前用于注册头像(type=avatar);聊天独立出来后图片/语音(image/audio)同走这套。
 *
 * 开发环境没有 MinIO:init 失败只打日志(fail-soft),prepare 时报"上传服务不可用"。
 */
@Slf4j
@Service
public class DzMinioService {

    /** 类型 → 允许扩展名;对象 key = {type}/{date}/{uuid}.{ext} */
    private static final Map<String, List<String>> ALLOWED_TYPES = Map.of(
            "avatar", List.of("jpg", "jpeg", "png", "webp"),
            "image", List.of("jpg", "jpeg", "png", "gif", "webp"),
            "audio", List.of("m4a", "mp3", "aac", "wav")
    );
    /** 类型 → 大小上限(字节)。前端头像已压到 ≤100KB,2MB 是防呆上限 */
    private static final Map<String, Long> MAX_SIZE = Map.of(
            "avatar", 2L * 1024 * 1024,
            "image", 10L * 1024 * 1024,
            "audio", 5L * 1024 * 1024
    );

    @Value("${dzpk.minio.endpoint:http://127.0.0.1:19010}")
    private String endpoint;
    @Value("${dzpk.minio.external-endpoint:https://cxchat.cocoaihj.com}")
    private String externalEndpoint;
    @Value("${dzpk.minio.access-key:minioadmin}")
    private String accessKey;
    @Value("${dzpk.minio.secret-key:minioadmin}")
    private String secretKey;
    @Value("${dzpk.minio.bucket:cx-dzpk}")
    private String bucket;

    private MinioClient client;          // 内网:建桶/管理
    private MinioClient externalClient;  // 外网:预签名(签名绑定 Host,必须用客户端可达的端点)
    private volatile boolean ready = false;

    @PostConstruct
    public void init() {
        try {
            client = MinioClient.builder().endpoint(endpoint).credentials(accessKey, secretKey).build();
            externalClient = MinioClient.builder().endpoint(externalEndpoint).credentials(accessKey, secretKey).build();
            if (!client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
                client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                log.info("[Minio] 自动创建桶: {}", bucket);
            }
            // 公开读策略:accessUrl 是不带签名的裸 GET,桶必须允许匿名下载
            client.setBucketPolicy(SetBucketPolicyArgs.builder().bucket(bucket).config(publicReadPolicy()).build());
            ready = true;
            log.info("[Minio] 就绪: endpoint={}, external={}, bucket={}", endpoint, externalEndpoint, bucket);
        } catch (Exception e) {
            log.warn("[Minio] 初始化失败(开发环境无 MinIO 属正常,上传功能不可用): {}", e.getMessage());
        }
    }

    public static class PresignedUpload {
        public String objectKey;
        public String presignedUrl;
        public String accessUrl;
    }

    /** 生成预签名 PUT(30 分钟有效);类型/扩展名/大小不合法抛 IllegalArgumentException */
    public PresignedUpload prepareUpload(String type, String fileName, long fileSize) {
        if (!ready) throw new IllegalStateException("上传服务不可用");
        List<String> exts = ALLOWED_TYPES.get(type);
        if (exts == null) throw new IllegalArgumentException("不支持的类型: " + type);
        String ext = extensionOf(fileName);
        if (!exts.contains(ext)) throw new IllegalArgumentException("不允许的扩展名: " + ext + ",允许: " + exts);
        long max = MAX_SIZE.getOrDefault(type, 10L * 1024 * 1024);
        if (fileSize <= 0 || fileSize > max) {
            throw new IllegalArgumentException("文件大小非法,最大 " + (max / 1024 / 1024) + "MB");
        }

        String objectKey = String.format("%s/%s/%s.%s",
                type, LocalDate.now(), UUID.randomUUID().toString().replace("-", ""), ext);
        try {
            PresignedUpload r = new PresignedUpload();
            r.objectKey = objectKey;
            r.presignedUrl = externalClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.PUT).bucket(bucket).object(objectKey)
                    .expiry(30, TimeUnit.MINUTES).build());
            r.accessUrl = stripSlash(externalEndpoint) + "/" + bucket + "/" + objectKey;
            return r;
        } catch (Exception e) {
            log.error("[Minio] 生成预签名 URL 失败: {}", e.getMessage());
            throw new IllegalStateException("生成上传地址失败");
        }
    }

    private String publicReadPolicy() {
        return "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Principal\":{\"AWS\":[\"*\"]}," +
                "\"Action\":[\"s3:GetObject\"],\"Resource\":[\"arn:aws:s3:::" + bucket + "/*\"]}]}";
    }

    private static String extensionOf(String fileName) {
        if (fileName == null) return "";
        int i = fileName.lastIndexOf('.');
        return i > 0 && i < fileName.length() - 1 ? fileName.substring(i + 1).toLowerCase() : "";
    }

    private static String stripSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
