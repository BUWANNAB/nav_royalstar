package com.ant.robot.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

@Slf4j
@Service
public class VersionService {
    private static final String VERSION_FILE = "version.info";
    private static final String VERSION_BACKUP = "version.info.bak";

    public String getCurrentVersion() {
        try {
            File file = new File(VERSION_FILE);
            if (file.exists()) {
                return Files.readString(file.toPath()).trim();
            }
        } catch (IOException e) {
            log.warn("读取版本文件失败", e);
            // 尝试从备份恢复
            try {
                Path backupPath = Paths.get(VERSION_BACKUP);
                if (Files.exists(backupPath)) {
                    Files.copy(backupPath, Paths.get(VERSION_FILE), StandardCopyOption.REPLACE_EXISTING);
                    return Files.readString(backupPath).trim();
                }
            } catch (IOException ex) {
                log.error("从备份恢复版本文件失败", ex);
            }
        }
        return "1.0.0"; // 默认版本
    }
    public String getRosVersion() {
        // 实现获取ROS版本号的逻辑
        return "1.0.0"; // 示例值，实际应从配置或系统获取
    }

    public void saveVersion(String version) {
        try {
            // 先备份当前版本
            Path currentPath = Paths.get(VERSION_FILE);
            if (Files.exists(currentPath)) {
                Files.copy(currentPath, Paths.get(VERSION_BACKUP), StandardCopyOption.REPLACE_EXISTING);
            }

            // 写入新版本
            Files.writeString(currentPath, version);
            log.info("版本已更新为: {}", version);
        } catch (IOException e) {
            log.error("保存版本文件失败", e);
            // 尝试恢复备份
            try {
                Path backupPath = Paths.get(VERSION_BACKUP);
                if (Files.exists(backupPath)) {
                    Files.copy(backupPath, Paths.get(VERSION_FILE), StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException ex) {
                log.error("恢复版本备份失败", ex);
            }
        }
    }

    public void rollbackVersion() {
        try {
            Path backupPath = Paths.get(VERSION_BACKUP);
            if (Files.exists(backupPath)) {
                Files.copy(backupPath, Paths.get(VERSION_FILE), StandardCopyOption.REPLACE_EXISTING);
                log.info("已回滚到上一个版本");
            } else {
                log.warn("没有可用的版本备份");
            }
        } catch (IOException e) {
            log.error("回滚版本失败", e);
        }
    }
}