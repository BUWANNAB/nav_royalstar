package com.ant.robot.service;

import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.zip.ZipFile;

@Service
public class RosOtaUpdateService {
    private static final Logger log = LoggerFactory.getLogger(RosOtaUpdateService.class);
    private static final String TEMP_DIR = System.getProperty("java.io.tmpdir") + "/ros_ota/";
    private static final String BACKUP_DIR = TEMP_DIR + "backup/";

    private final MqttClientService mqttClientService;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();

    public RosOtaUpdateService(@Lazy MqttClientService mqttClientService) {
        this.mqttClientService = mqttClientService;
        initialize();
    }

    private void initialize() {
        try {
            Files.createDirectories(Paths.get(TEMP_DIR));
            Files.createDirectories(Paths.get(BACKUP_DIR));
            log.info("ROS OTA服务初始化完成，临时目录: {}", TEMP_DIR);
        } catch (IOException e) {
            log.error("创建临时目录失败", e);
        }
    }

    public void performRosOtaUpdate(String firmwareUrl, String newVersion, String taskId) {
        executor.execute(() -> {
            File newPackage = null;
            try {
                // 1. 发送接收状态
                sendOtaStatus(taskId, OtaStatus.RECEIVED, 0, "已收到ROS更新指令");

                // 2. 下载固件
                sendOtaStatus(taskId, OtaStatus.DOWNLOADING, 0, "开始下载ROS固件");
                newPackage = new File(TEMP_DIR + "ros_package_v" + newVersion + ".zip");
                downloadWithProgress(firmwareUrl, newPackage, taskId);

                // 3. 验证包文件
                sendOtaStatus(taskId, OtaStatus.INSTALLING, 50, "验证ROS固件包");
                if (!validateRosPackage(newPackage)) {
                    throw new RuntimeException("ROS固件验证失败");
                }

                // 4. 备份当前ROS节点
                sendOtaStatus(taskId, OtaStatus.INSTALLING, 70, "备份当前ROS节点");
                File currentApp = getCurrentRosNodePath();
                File backupFile = new File(BACKUP_DIR + currentApp.getName() + ".bak");
                Files.copy(currentApp.toPath(), backupFile.toPath(), StandardCopyOption.REPLACE_EXISTING);

                // 5. 准备更新脚本
                sendOtaStatus(taskId, OtaStatus.INSTALLING, 80, "准备ROS更新");
                File updateScript = createRosUpdateScript(currentApp, newPackage, newVersion, taskId);

                // 6. 执行更新脚本
                sendOtaStatus(taskId, OtaStatus.INSTALLING, 90, "执行ROS更新");
                if (!executeUpdateScript(updateScript)) {
                    throw new RuntimeException("ROS更新脚本执行失败");
                }

                // 7. 发送准备重启状态
                sendOtaStatus(taskId, OtaStatus.REBOOTING, 100, "准备重启ROS节点");

                // 8. 延迟确保状态发送完成
                Thread.sleep(1000);

                // 9. 重启ROS节点
                restartRosNode();

            } catch (Exception e) {
                log.error("ROS OTA更新失败", e);
                sendOtaStatus(taskId, OtaStatus.FAILED, 0, "ROS更新失败: " + e.getMessage());

                // 尝试回滚
                try {
                    rollbackRosNode();
                } catch (Exception ex) {
                    log.error("ROS回滚失败", ex);
                }
            } finally {
                cleanupTempFiles(newPackage);
            }
        });
    }

    private void downloadWithProgress(String url, File dest, String taskId) throws IOException {
        try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
            HttpGet request = new HttpGet(url);

            try (CloseableHttpResponse response = httpClient.execute(request);
                 FileOutputStream out = new FileOutputStream(dest)) {

                long totalSize = response.getEntity().getContentLength();
                long downloaded = 0;
                byte[] buffer = new byte[8192];
                int len;

                try (InputStream in = response.getEntity().getContent()) {
                    while ((len = in.read(buffer)) != -1) {
                        out.write(buffer, 0, len);
                        downloaded += len;

                        // 每下载1MB或完成时报告进度
                        if (downloaded % (1024 * 1024) == 0 || downloaded == totalSize) {
                            int progress = (int) (downloaded * 100 / totalSize);
                            sendOtaStatus(
                                    taskId,
                                    OtaStatus.DOWNLOADING,
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

    private boolean validateRosPackage(File packageFile) {
        // 检查文件是否存在
        if (!packageFile.exists()) {
            log.error("ROS包文件不存在: {}", packageFile.getAbsolutePath());
            return false;
        }

        // 检查文件大小
        if (packageFile.length() == 0) {
            log.error("ROS包文件为空");
            return false;
        }

        // 检查是否为有效的ZIP文件
        try (ZipFile zipFile = new ZipFile(packageFile)) {
            // 检查是否包含必要的ROS文件
            if (zipFile.getEntry("package.xml") == null) {
                log.error("ROS包缺少package.xml文件");
                return false;
            }

            // 检查是否有可执行文件
            boolean hasExecutable = zipFile.stream()
                    .anyMatch(entry -> entry.getName().startsWith("bin/") || entry.getName().startsWith("lib/"));

            if (!hasExecutable) {
                log.error("ROS包缺少可执行文件");
                return false;
            }

            return true;
        } catch (IOException e) {
            log.error("验证ROS包失败", e);
            return false;
        }
    }

    private File getCurrentRosNodePath() {
        // 获取当前ROS节点的路径
        // 这里需要根据实际部署情况调整
        return new File("/opt/ros/current/bin/ros_node");
    }

    private File createRosUpdateScript(File currentApp, File newPackage, String version, String taskId) throws IOException {
        String scriptContent = generateUpdateScriptContent(currentApp, newPackage, version, taskId);
        File scriptFile = new File(TEMP_DIR + "ros_update_v" + version + ".sh");

        Files.write(scriptFile.toPath(), scriptContent.getBytes());
        scriptFile.setExecutable(true);

        return scriptFile;
    }

    private String generateUpdateScriptContent(File currentApp, File newPackage, String version, String taskId) {
        return "#!/bin/bash\n" +
                "# ROS OTA Update Script for version " + version + " (Task ID: " + taskId + ")\n" +
                "set -e\n\n" +
                "echo 'Starting ROS OTA update for version " + version + "'\n" +
                "echo 'Waiting for ROS node to exit...'\n" +
                "sleep 5\n\n" +
                "echo 'Backing up current version...'\n" +
                "BACKUP_PATH=\"" + BACKUP_DIR + currentApp.getName() + ".bak\"\n" +
                "cp \"" + currentApp.getAbsolutePath() + "\" \"$BACKUP_PATH\"\n" +
                "if [ $? -ne 0 ]; then\n" +
                "   echo 'Backup failed'\n" +
                "   exit 1\n" +
                "fi\n\n" +
                "echo 'Extracting new package...'\n" +
                "unzip -o \"" + newPackage.getAbsolutePath() + "\" -d \"" + currentApp.getParentFile().getAbsolutePath() + "\"\n" +
                "if [ $? -ne 0 ]; then\n" +
                "   echo 'Extraction failed, restoring backup...'\n" +
                "   mv \"$BACKUP_PATH\" \"" + currentApp.getAbsolutePath() + "\"\n" +
                "   exit 1\n" +
                "fi\n\n" +
                "echo 'Setting executable permissions...'\n" +
                "chmod +x \"" + currentApp.getAbsolutePath() + "\"\n\n" +
                "echo 'Starting new version...'\n" +
                "nohup " + currentApp.getAbsolutePath() + " >/dev/null 2>&1 &\n\n" +
                "echo 'Cleaning up...'\n" +
                "rm -f \"" + newPackage.getAbsolutePath() + "\"\n\n" +
                "echo 'ROS OTA update completed successfully'\n" +
                "# Self-delete\n" +
                "rm -- \"$0\"\n";
    }

    private boolean executeUpdateScript(File scriptFile) throws IOException, InterruptedException {
        ProcessBuilder processBuilder = new ProcessBuilder("/bin/bash", scriptFile.getAbsolutePath());
        processBuilder.redirectErrorStream(true);
        processBuilder.redirectOutput(ProcessBuilder.Redirect.INHERIT);

        Process process = processBuilder.start();
        return process.waitFor() == 0;
    }

    private void restartRosNode() {
        // 修改为使用系统命令重启ROS节点，而不是使用ROS Java API
        try {
            // 示例：通过执行系统命令重启ROS节点
            // 实际命令需要根据您的ROS部署调整
            String[] cmd = {
                    "/bin/bash",
                    "-c",
                    "sudo systemctl restart ros-node.service"
            };

            Process process = Runtime.getRuntime().exec(cmd);
            int exitCode = process.waitFor();

            if (exitCode == 0) {
                log.info("ROS节点已成功重启");
            } else {
                log.error("重启ROS节点失败，退出码: {}", exitCode);
                throw new RuntimeException("重启ROS节点失败");
            }
        } catch (IOException | InterruptedException e) {
            log.error("执行ROS节点重启命令失败", e);
            throw new RuntimeException("执行ROS节点重启命令失败", e);
        }
    }

    private void rollbackRosNode() throws IOException {
        File currentApp = getCurrentRosNodePath();
        File backupFile = new File(BACKUP_DIR + currentApp.getName() + ".bak");

        if (backupFile.exists()) {
            log.info("开始回滚ROS节点");
            Files.copy(backupFile.toPath(), currentApp.toPath(), StandardCopyOption.REPLACE_EXISTING);
            currentApp.setExecutable(true);
            restartRosNode();
            log.info("ROS节点回滚完成");
        } else {
            log.warn("找不到备份文件，无法回滚");
        }
    }

    private void sendOtaStatus(String taskId, OtaStatus status, int progress, String message) {
        try {
            JSONObject statusMsg = new JSONObject();
            statusMsg.put("taskId", taskId);
            statusMsg.put("status", status.name());
            statusMsg.put("progress", progress);
            statusMsg.put("message", message);
            statusMsg.put("timestamp", System.currentTimeMillis());

            String topic = "/s/ota/ros/" + mqttClientService.getClientId();
            mqttClientService.publish(topic, statusMsg.toString());
            log.info("ROS OTA状态上报: {}", statusMsg);
        } catch (Exception e) {
            log.error("ROS OTA状态上报失败", e);
        }
    }

    private void cleanupTempFiles(File... files) {
        Arrays.stream(files)
                .filter(file -> file != null && file.exists())
                .forEach(file -> {
                    try {
                        Files.deleteIfExists(file.toPath());
                    } catch (IOException e) {
                        log.warn("删除临时文件失败: " + file.getAbsolutePath(), e);
                    }
                });
    }

    public enum OtaStatus {
        RECEIVED,        // 已接收
        DOWNLOADING,     // 下载中
        DOWNLOAD_FAILED, // 下载失败
        INSTALLING,      // 安装中
        INSTALL_FAILED,  // 安装失败
        REBOOTING,       // 重启中
        SUCCESS,         // 成功
        FAILED,          // 失败
        ROLLBACK         // 回滚
    }
}