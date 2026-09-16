package com.ant.robot.utils;

import com.ant.robot.controller.RevdataController;
import com.ant.robot.model.domain.Task;
import com.ant.robot.service.TaskService;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 任务冲突检测工具类（静态方法）
 */
public final class TaskConflictChecker {

    // 私有构造防止实例化
    private TaskConflictChecker() {}

    /**
     * 检查任务冲突
     * @param taskService 需传入TaskService实例
     * @return 冲突原因，null表示无冲突
     */
    public static String checkConflict(TaskService taskService) {
        // 1. 检查PLC当前路线状态
        if (RevdataController.routeEnd == 0) {
            return "当前有路线正在执行";
        }

        // 2. 检查即将执行的定时任务
        if (hasScheduledTaskSoon(taskService)) {
            return "半小时内有定时任务即将执行";
        }

        return null;
    }

    private static boolean hasScheduledTaskSoon(TaskService taskService) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime threshold = now.plusMinutes(30);

        List<Task> tasks = taskService.list();
        for (Task task : tasks) {
            if (isTaskScheduledSoon(task, now, threshold)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isTaskScheduledSoon(Task task, LocalDateTime now, LocalDateTime threshold) {
        try {
            if ("single".equals(task.getExecutionType())) {
                LocalDateTime execTime = LocalDateTime.parse(task.getSingleExecution());
                return execTime.isAfter(now) && execTime.isBefore(threshold);
            }
            else if ("repeat".equals(task.getExecutionType())) {
                if (task.getNextExecution() != null) {
                    LocalDateTime nextExec = LocalDateTime.parse(task.getNextExecution());
                    return nextExec.isAfter(now) && nextExec.isBefore(threshold);
                }
            }
        } catch (Exception e) {
            // 忽略格式错误的任务
        }
        return false;
    }
}