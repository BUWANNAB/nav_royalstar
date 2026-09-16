package com.ant.robot.service.scheduler;

import com.ant.robot.utils.TaskConflictChecker;
import com.ant.robot.controller.RouteController;
import com.ant.robot.controller.SendToPlcController;
import com.ant.robot.model.domain.Task;
import com.ant.robot.service.ModbusTcpClientService;
import com.ant.robot.service.TaskService;
import com.ant.robot.service.TcpClientService;
import com.ant.robot.websocket.WebSocketServer;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;

@Component
@Slf4j
public class CustomTaskScheduler {

    @Autowired
    private TaskService taskService;
    @Autowired
    private RouteController routeController;
    @Autowired
    private WebSocketServer webSocketServer;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // 每分钟检查一次任务
    @Scheduled(cron = "0 * * * * ?")
    public void checkAndExecuteTasks() {
        List<Task> tasks = taskService.list();
        LocalDate today = LocalDate.now();
        DayOfWeek currentDayOfWeek = today.getDayOfWeek();
        LocalTime currentTime = LocalTime.now();
        for (Task task : tasks) {
            // 冲突检测
            String conflictReason = TaskConflictChecker.checkConflict(taskService);
            if (conflictReason != null) {
                //log.warn("任务{}执行冲突: {}", task.getId(), conflictReason);
                updateTaskStatus(task.getId(), "waiting", conflictReason);
                continue;
            }
            if (shouldExecuteTask(task, currentDayOfWeek, currentTime)) {
                executeTask(task);
            }
        }
    }

    private boolean shouldExecuteTask(Task task, DayOfWeek currentDayOfWeek, LocalTime currentTime) {
        if ("single".equalsIgnoreCase(task.getExecutionType())) {
            return checkSingleExecution(task, currentTime);
        } else if ("repeat".equalsIgnoreCase(task.getExecutionType())) {
            return checkRepeatExecution(task, currentDayOfWeek, currentTime);
        }
        return false;
    }

    private boolean checkSingleExecution(Task task, LocalTime currentTime) {
        try {
            if (task.getSingleExecution() == null || task.getSingleExecution().isEmpty()) {
                return false;
            }
            // 解析ISO-8601格式的时间
            LocalDateTime executionDateTime = LocalDateTime.parse(
                    task.getSingleExecution(),
                    DateTimeFormatter.ISO_LOCAL_DATE_TIME
            );
            // 比较日期和时间
            LocalDate today = LocalDate.now();
            return executionDateTime.toLocalDate().equals(today) &&
                    executionDateTime.getHour() == currentTime.getHour() &&
                    executionDateTime.getMinute() == currentTime.getMinute();
        } catch (Exception e) {
            log.error("解析单次执行时间失败: " + task.getSingleExecution(), e);
            return false;
        }
    }

    private boolean checkRepeatExecution(Task task, DayOfWeek currentDayOfWeek, LocalTime currentTime) {
        try {
            if (task.getWeekdays() == null || task.getWeekdays().isEmpty() ||
                    !task.getWeekdays().contains(String.valueOf(currentDayOfWeek.getValue()))) {
                return false;
            }

            if (task.getRepeatExecutionTime() == null || task.getRepeatExecutionTime().isEmpty()) {
                return false;
            }

            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("HH:mm");
            LocalTime executionTime = LocalTime.parse(task.getRepeatExecutionTime(), formatter);

            return currentTime.getHour() == executionTime.getHour() &&
                    currentTime.getMinute() == executionTime.getMinute();
        } catch (Exception e) {
            return false;
        }
    }

    private void executeTask(Task task) {
        try {
            // 1. 检查任务是否已在运行状态
            if ("running".equalsIgnoreCase(task.getStatus())) {
                log.warn("任务 {} 已在运行状态，跳过执行", task.getId());
                return;
            }

            // 2. 验证关联路线是否存在
            try {
                Long routeId = Long.parseLong(task.getRouteId());
                List<Object> routeData = routeController.querymapRouteDetailData(routeId).getData();
                if (routeData == null || routeData.isEmpty()) {
                    log.error("任务 {} 关联的路线不存在", task.getId());
                    updateTaskStatus(task.getId(), "failed", "关联路线不存在");
                    return;
                }
            } catch (NumberFormatException e) {
                log.error("任务 {} 的路线ID格式错误", task.getId(), e);
                updateTaskStatus(task.getId(), "failed", "路线ID格式错误");
                return;
            }

            // 3. 更新最后执行时间
            updateLastExecution(task.getId());

            // 4. 设置任务为运行状态
            updateTaskStatus(task.getId(), "running", "任务开始执行");

            // 5. 执行任务逻辑（原有代码）
            Long routeId = Long.parseLong(task.getRouteId());
            List<Object> routeData = routeController.querymapRouteDetailData(routeId).getData();

            JSONArray jsonArray = new JSONArray();
            routeData.forEach(jsonArray::put);

            JSONObject jsonObject = new JSONObject();
            jsonObject.put("action", "gnsspoint");
            jsonObject.put("points", jsonArray);

            sendToPlc(jsonObject);

            if (ModbusTcpClientService.writeSingleCoil(1, true)) {
                log.info("运行成功");
                TcpClientService.sendProgressUpdate("启动","成功","1");

                updateTaskStatus(task.getId(), "completed", "任务执行成功");
            } else {
                log.warn("运行失败");
                TcpClientService.sendProgressUpdate("启动","失败","0");
                updateTaskStatus(task.getId(), "failed", "PLC指令执行失败");
            }

            // 6. 如果是重复任务，更新下次执行时间
            if ("repeat".equalsIgnoreCase(task.getExecutionType())) {
                updateNextExecution(task);
            }

            // 7. 如果是单次任务，执行后删除
            if ("single".equalsIgnoreCase(task.getExecutionType())) {
                taskService.removeById(task.getId());
            }
        } catch (Exception e) {
            log.error("执行任务 {} 时发生错误", task.getId(), e);
            updateTaskStatus(task.getId(), "failed", "异常: " + e.getMessage());
        }
    }

    // 新增辅助方法：更新最后执行时间
    private void updateLastExecution(Long taskId) {
        Task task = new Task();
        task.setId(taskId);
        task.setLastExecution(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        taskService.updateById(task);
    }

    // 新增辅助方法：更新下次执行时间（仅对重复任务）
    private void updateNextExecution(Task task) {
        if ("repeat".equalsIgnoreCase(task.getExecutionType())) {
            try {
                DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("HH:mm");
                LocalTime executionTime = LocalTime.parse(task.getRepeatExecutionTime(), timeFormatter);

                LocalDateTime nextExecution = LocalDateTime.now()
                        .withHour(executionTime.getHour())
                        .withMinute(executionTime.getMinute())
                        .plusDays(1); // 下一天同一时间

                Task updateTask = new Task();
                updateTask.setId(task.getId());
                updateTask.setNextExecution(nextExecution.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
                taskService.updateById(updateTask);
            } catch (Exception e) {
                log.error("计算任务 {} 的下次执行时间时出错", task.getId(), e);
            }
        }
    }

    // 新增辅助方法：更新任务状态
    private void updateTaskStatus(Long taskId, String status, String message) {
        Task task = new Task();
        task.setId(taskId);
        task.setStatus(status);
        if (message != null) {
            task.setRetryOnFailure(message);
        }
        taskService.updateById(task);
    }

    private void sendToPlc(JSONObject jsonObject) {
        // 检查任务是否已在运行
        if (!webSocketServer.isPlcTaskRunning.compareAndSet(false, true)) {
            System.err.println("已有PLC任务正在执行，无法重复提交");
            throw new IllegalStateException("已有任务正在执行");
        }
        try {
            Future<?> future = webSocketServer.executorService.submit(() -> {
                try {
                    ModbusTcpClientService.acquirePriorityLock(ModbusTcpClientService.PATH_PRIORITY);
                    ModbusTcpClientService.writeSingleCoil(3, true);
                    SendToPlcController.pathpointToPlc(jsonObject);
                } catch (Exception e) {
                    System.err.println("PLC任务执行异常");
                    e.printStackTrace();
                    throw new RuntimeException("PLC任务异常", e);
                } finally {
                    ModbusTcpClientService.releasePriorityLock();
                    webSocketServer.isPlcTaskRunning.set(false);
                }
            });
        } catch (RejectedExecutionException e) {
            webSocketServer.isPlcTaskRunning.set(false); // 提交失败时重置状态
            System.err.println("任务提交被拒绝，线程池已满");
            throw e;
        }
    }
}