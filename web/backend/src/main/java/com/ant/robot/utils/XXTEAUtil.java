package com.ant.robot.utils;

import cn.hutool.crypto.symmetric.XXTEA;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Random;

@Slf4j
public class XXTEAUtil {
    private static final String CHAR_POOL = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

    public static void main(String[] args) {
        // 测试数据
        String original = "{\"taskId\":\"1\",\"routeId\":\"1\"}";
        String key = "T8yDTqJqNd";

        // 加密测试
        String encrypted = XXTEAUtil.encrypt(original, key);
        System.out.println("加密结果: " + encrypted);

        // 解密测试
        String decrypted = XXTEAUtil.decrypt(encrypted, key);
        System.out.println("解密结果: " + decrypted);
        System.out.println("解密是否成功: " + original.equals(decrypted));

        // 验证与示例一致
        String exampleEncrypted = "+e+vNf+TsDkOu4AO7BUQ5/e5RgiqZ2JVW8Bm+1nDKV9YHfapeU31Ektjktc=";
        String exampleDecrypted = XXTEAUtil.decrypt(exampleEncrypted, key);
        System.out.println("示例解密结果: " + exampleDecrypted);
    }

    // 随机密钥生成方法
    public static String generateRandomKey(int length) {
        Random random = new Random();
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(CHAR_POOL.charAt(random.nextInt(CHAR_POOL.length())));
        }
        return sb.toString();
    }

    // 使用设备密钥加密随机密钥
    public static String encryptKey(String rNum, String deviceCode) {
        return encrypt(rNum, deviceCode);
    }

    // 核心加密方法
    public static String encrypt(String text, String key) {
        if (text == null || key == null) return null;
        try {
            // 直接使用XXTEA类进行加密
            XXTEA xxtea = new XXTEA(key.getBytes(StandardCharsets.UTF_8));
            byte[] encrypted = xxtea.encrypt(text.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(encrypted);
        } catch (Exception e) {
            log.error("加密失败: text={}, key={}", text, key, e);
            return null;
        }
    }

    // 核心解密方法
    public static String decrypt(String base64Data, String key) {
        if (base64Data == null || key == null) return null;
        try {
            // 移除可能存在的空格和换行
            String cleanData = base64Data.trim().replaceAll("\\s", "");

            // Base64解码
            byte[] data;
            try {
                data = Base64.getDecoder().decode(cleanData);
            } catch (IllegalArgumentException e) {
                log.error("Base64解码失败: {}", cleanData);
                return null;
            }

            // 使用XXTEA类进行解密
            XXTEA xxtea = new XXTEA(key.getBytes(StandardCharsets.UTF_8));
            byte[] decrypted = xxtea.decrypt(data);

            return new String(decrypted, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("解密失败: data={}, key={}", base64Data, key, e);
            return null;
        }
    }
}