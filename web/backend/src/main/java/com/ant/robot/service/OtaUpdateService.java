package com.ant.robot.service;

import cn.hutool.json.JSONObject;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.HttpEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

@Slf4j
@Service
public class OtaUpdateService {
    private static final String TEMP_DIR = System.getProperty("java.io.tmpdir") + "/ota/";
    private final MqttClientService mqttClientService;

    @Autowired
    public OtaUpdateService(@Lazy MqttClientService mqttClientService) {
        this.mqttClientService = mqttClientService;
    }

    @PostConstruct
    public void init() throws IOException {
        Files.createDirectories(Paths.get(TEMP_DIR));
    }

    public void performOtaUpdate(String firmwareUrl, String newVersion, String taskId) {
        File newJar = null;
        try {
            // 1. 发送接收状态
            sendOtaResult(taskId, "RECEIVED", 0, "已收到更新指令");

            // 2. 下载固件
            sendOtaResult(taskId, "DOWNLOADING", 0, "开始下载固件");
            newJar = new File(TEMP_DIR + "app-new.jar");
            downloadWithProgress(firmwareUrl, newJar, taskId);

            // 3. 验证JAR文件
            sendOtaResult(taskId, "INSTALLING", 50, "验证固件");
            if (!isValidJar(newJar)) {
                throw new RuntimeException("固件验证失败");
            }

            // 4. 准备更新脚本
            sendOtaResult(taskId, "INSTALLING", 70, "准备更新");
            File oldJar = getCurrentJarFile();
            File updateScript = createUpdateScript(oldJar, newJar);

            // 5. 验证更新脚本
            if (!updateScript.exists()) {
                throw new RuntimeException("更新脚本创建失败");
            }

            // 6. 发送准备重启状态
            sendOtaResult(taskId, "REBOOTING", 90, "准备重启");

            // 7. 执行更新并重启
            boolean success = executeUpdateScript(updateScript);
            if (!success) {
                throw new RuntimeException("更新脚本执行失败");
            }

            // 8. 发送成功状态
            sendOtaResult(taskId, "SUCCESS", 100, "更新成功");

            // 9. 添加延迟确保状态发送完成
            Thread.sleep(1000);
            System.exit(0);

        } catch (Exception e) {
            log.error("OTA更新失败", e);
            sendOtaResult(taskId, "FAILED", 0, "更新失败: " + e.getMessage());
        } finally {
            cleanupTempFiles(newJar);
        }
    }

    private void downloadWithProgress(String url, File dest, String taskId) throws IOException {
        try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
            HttpGet request = new HttpGet(url);

            try (CloseableHttpResponse response = httpClient.execute(request);
                 FileOutputStream out = new FileOutputStream(dest)) {

                HttpEntity entity = response.getEntity();
                long totalSize = entity.getContentLength();
                long downloaded = 0;
                byte[] buffer = new byte[8192];
                int len;

                try (InputStream in = entity.getContent()) {
                    while ((len = in.read(buffer)) != -1) {
                        out.write(buffer, 0, len);
                        downloaded += len;

                        // 每下载1MB或完成时报告进度
                        if (downloaded % (1024 * 1024) == 0 || downloaded == totalSize) {
                            int progress = (int) (downloaded * 100 / totalSize);
                            sendOtaResult(
                                    taskId,
                                    "DOWNLOADING",
                                    progress,
                                    String.format("下载中... (%.1fMB/%.1fMB)",
                                            downloaded / (1024.0 * 1024.0),
                                            totalSize / (1024.0 * 1024.0))
                            );
                        }
                    }
                }
            }
        }
    }

    private void sendOtaResult(String taskId, String status, int progress, String message) {
        try {
            JSONObject resultMsg = new JSONObject();
            resultMsg.put("taskId", taskId);
            resultMsg.put("status", status);
            resultMsg.put("progress", progress);
            resultMsg.put("message", message);
            resultMsg.put("timestamp", System.currentTimeMillis());

            String resultTopic = "/s/ota/result/" + mqttClientService.getClientId();
            mqttClientService.publish(resultTopic, resultMsg.toString());
            log.info("OTA状态上报: {}", resultMsg);

        } catch (Exception e) {
            log.error("OTA状态上报失败", e);
        }
    }

    private File getCurrentJarFile() {
        try {
            String path = getClass()
                    .getProtectionDomain()
                    .getCodeSource()
                    .getLocation()
                    .toURI()
                    .getPath();
            return new File(path);
        } catch (URISyntaxException e) {
            throw new RuntimeException("Failed to get JAR path", e);
        }
    }

    private File createUpdateScript(File oldJar, File newJar) throws IOException {
        String scriptContent;
        String scriptName;
        String javaCmd = "\"" + System.getProperty("java.home") + File.separator + "bin" + File.separator + "java" + "\"";

        if (System.getProperty("os.name").toLowerCase().contains("win")) {
            scriptName = "update.bat";
            scriptContent = String.format(
                    "@echo off\r\n" +
                            "chcp 65001 >nul\r\n" +
                            "setlocal enabledelayedexpansion\r\n" +
                            "\r\n" +
                            ":: 保存脚本自身路径\r\n" +
                            "set SCRIPT_PATH=%%~f0\r\n" +
                            "\r\n" +
                            "echo Old JAR: %s\r\n" +
                            "echo New JAR: %s\r\n" +
                            "echo Java CMD: %s\r\n" +
                            "echo Current Dir: %%cd%%\r\n" +
                            "\r\n" +
                            "echo Waiting for application to exit...\r\n" +
                            "timeout /t 10 /nobreak >nul\r\n" +
                            "\r\n" +
                            "echo Replacing JAR file...\r\n" +
                            "xcopy /Y \"%s\" \"%s\" >nul\r\n" +
                            "if %%errorlevel%% neq 0 (\r\n" +
                            "   echo Failed to copy from:\r\n" +
                            "   echo   \"%s\"\r\n" +
                            "   echo to:\r\n" +
                            "   echo   \"%s\"\r\n" +
                            "   pause\r\n" +
                            "   exit /b 1\r\n" +
                            ")\r\n" +
                            "\r\n" +
                            "echo Starting new version...\r\n" +
                            "start \"\" \"\" %s -jar \"%s\"\r\n" +
                            "\r\n" +
                            ":: 延迟自删除\r\n" +
                            "ping -n 3 127.0.0.1 >nul\r\n" +
                            "if exist \"!SCRIPT_PATH!\" del \"!SCRIPT_PATH!\"\r\n",
                    oldJar.getAbsolutePath(),
                    newJar.getAbsolutePath(),
                    javaCmd,
                    newJar.getAbsolutePath(),
                    oldJar.getAbsolutePath(),
                    newJar.getAbsolutePath(),
                    oldJar.getAbsolutePath(),
                    javaCmd,
                    oldJar.getAbsolutePath()
            );
            log.info("Old JAR path: {}", oldJar.getAbsolutePath());
            log.info("New JAR path: {}", newJar.getAbsolutePath());
            log.info("Java path: {}", javaCmd);
        } else {
            scriptName = "update.sh";
            scriptContent = String.format(
                    "#!/bin/bash\n" +
                            "echo 'Waiting for application to exit...'\n" +
                            "sleep 5\n" +
                            "echo 'Replacing JAR file...'\n" +
                            "cp \"%s\" \"%s\"\n" +
                            "if [ $? -ne 0 ]; then\n" +
                            "   echo 'Update failed'\n" +
                            "   exit 1\n" +
                            "fi\n" +
                            "echo 'Starting new version...'\n" +
                            "nohup \"%s\" -jar \"%s\" >/dev/null 2>&1 &\n" +
                            "rm -- \"$0\"\n",
                    newJar.getAbsolutePath(),
                    oldJar.getAbsolutePath(),
                    javaCmd,
                    oldJar.getAbsolutePath()
            );
        }

        File scriptFile = new File(TEMP_DIR + scriptName);
        Files.write(scriptFile.toPath(), scriptContent.getBytes(StandardCharsets.UTF_8));

        if (!System.getProperty("os.name").toLowerCase().contains("win")) {
            scriptFile.setExecutable(true);
        }

        return scriptFile;
    }

    private boolean executeUpdateScript(File scriptFile) throws IOException, InterruptedException {
        String command;
        if (System.getProperty("os.name").toLowerCase().contains("win")) {
            command = "cmd.exe /c start /wait " + scriptFile.getAbsolutePath();
        } else {
            command = "nohup " + scriptFile.getAbsolutePath() + " >/dev/null 2>&1 &";
        }

        Process process = Runtime.getRuntime().exec(command);
        if (System.getProperty("os.name").toLowerCase().contains("win")) {
            return process.waitFor() == 0;
        }
        return true;
    }

    private void cleanupTempFiles(File... files) {
        for (File file : files) {
            if (file != null && file.exists()) {
                try {
                    Files.deleteIfExists(file.toPath());
                } catch (IOException e) {
                    log.warn("删除临时文件失败: " + file.getAbsolutePath(), e);
                }
            }
        }
    }

    private boolean isValidJar(File jarFile) {
        try (JarFile jar = new JarFile(jarFile)) {
            Manifest manifest = jar.getManifest();
            if (manifest == null) {
                log.error("JAR文件缺少清单文件");
                return false;
            }

            String mainClass = manifest.getMainAttributes().getValue("Main-Class");
            if (mainClass == null || mainClass.isEmpty()) {
                log.error("JAR文件缺少Main-Class属性");
                return false;
            }

            String mainClassPath = mainClass.replace('.', '/') + ".class";
            if (jar.getEntry(mainClassPath) == null) {
                log.error("JAR文件缺少主类: " + mainClass);
                return false;
            }

            return true;
        } catch (Exception e) {
            log.error("JAR验证失败", e);
            return false;
        }
    }
}