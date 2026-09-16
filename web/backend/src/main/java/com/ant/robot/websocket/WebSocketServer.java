package com.ant.robot.websocket;

import com.ant.robot.service.ROS2CommunicationService;
import com.ant.robot.utils.TaskConflictChecker;
import com.ant.robot.controller.SendToPlcController;
import com.ant.robot.model.domain.PointInfo;
import com.ant.robot.service.ModbusTcpClientService;
import com.ant.robot.service.TaskService;
import com.ant.robot.service.TcpClientService;
import jakarta.websocket.server.PathParam;
import lombok.extern.slf4j.Slf4j;
import org.apache.tomcat.util.threads.ThreadPoolExecutor;
import org.json.JSONException;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Component
@Slf4j
public class WebSocketServer extends TextWebSocketHandler {

    private static int onlineCount = 0;
    public static final CopyOnWriteArraySet<WebSocketServer> webSocketSet = new CopyOnWriteArraySet<>();
    private WebSocketSession session;
    private String sid = "";
    private final AtomicBoolean lock = new AtomicBoolean(false);
    public static int Tasktype;//任务类型标志位
    public static boolean Operation_sign;//运行标志
    private static JSONObject globalJsonObject = new JSONObject();
    private static volatile long lastHeartbeatResponseTime = 0;
    private static volatile long lastRemoteCtrlResponseTime = 0;
    private static final long HEARTBEAT_RESPONSE_INTERVAL = 1000; // 1秒间隔
    // 添加以下成员变量
    public static Queue<PointInfo> pendingPoints = new ConcurrentLinkedQueue<>(); // 待发送的点缓存
    private static volatile boolean isSendingPoints = false; // 是否正在发送点
    public final AtomicBoolean isPlcTaskRunning = new AtomicBoolean(false);

    @Autowired
    private TcpClientService tcpClientService;
    @Autowired
    private SendToPlcController sendtoplc;
    @Autowired
    private TaskService taskService;

    //启动时打印日志
    public WebSocketServer() {
        log.info("WebSocket 服务器已启动，准备接受连接...");
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        this.session = session;
        this.sid = session.getId();  // 使用 session ID 作为 sid
        webSocketSet.add(this);
        addOnlineCount();
        log.info("新窗口监听: " + sid + "，当前在线人数: " + getOnlineCount());
        // 延迟发送连接成功消息
        CompletableFuture.runAsync(() -> {
            try {
                Thread.sleep(100); // 短暂延迟确保连接完全建立
                sendMessage("连接成功");
            } catch (Exception e) {
                log.error("发送连接成功消息失败", e);
            }
        });
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        webSocketSet.remove(this);
        subOnlineCount();
        log.warn("连接关闭！当前在线人数: {}", Optional.of(getOnlineCount()));
    }

    @Override
    public void handleTextMessage(WebSocketSession session, TextMessage message) {
        // JSON 消息解析
        try {
            JSONObject JsonObject = new JSONObject(message.getPayload());
            taskCheck(JsonObject);
        } catch (Exception e) {
            log.error("消息解析异常: ", e);
        }
    }

    private void taskCheck (JSONObject JsonObject) throws JSONException, IOException, InterruptedException {
        String action = JsonObject.getString("action");
        switch (action) {
            case "gnsspoint"://发送路径
                // 检查任务是否已在运行
                String conflictReason = TaskConflictChecker.checkConflict(taskService);
                if (conflictReason != null) {
                    JSONObject response = new JSONObject()
                            .put("action", "taskRejected")
                            .put("reason", conflictReason)
                            .put("timestamp", System.currentTimeMillis());
                    sendMessage(response.toString());
                    return;
                }
                try {
                    Future<?> future = executorService.submit(() -> {
                        try {
                            ModbusTcpClientService.acquirePriorityLock(ModbusTcpClientService.PATH_PRIORITY);
                            ModbusTcpClientService.writeSingleCoil(3, true);
                            SendToPlcController.pathpointToPlc(JsonObject);
                        }   catch (Exception e) {
                            log.error("PLC任务执行异常", e);
                            throw new RuntimeException("PLC任务异常", e);
                        } finally {
                            ModbusTcpClientService.releasePriorityLock();
                            isPlcTaskRunning.set(false);
                        }
                    });
                } catch (RejectedExecutionException e) {
                    isPlcTaskRunning.set(false); // 提交失败时重置状态
                    throw e;
                }
                break;
            case "WebRemoteCtrlData"://遥控
                long RCcurrentTime = System.currentTimeMillis();
                if (/*ModbusTcpClientService.connectionStatus.get() == 2 &&*/ RCcurrentTime - lastRemoteCtrlResponseTime > 100){
                    //log.info("RCcurrentTime {}, lastRemoteCtrlResponseTime {}", Optional.of(RCcurrentTime),lastRemoteCtrlResponseTime);
                    lastRemoteCtrlResponseTime = RCcurrentTime;
                    //SendToPlcController.remotectrlInfoToPlc(JsonObject);
                    ROS2CommunicationService.publishWebRemoteCtrlData(String.valueOf(JsonObject));
                }
                break;
            case "car_run_star": //车辆启停控制
                byte car_run_stop = (byte)(JsonObject.getInt("data"));
                if (car_run_stop == 1) { //运行
                    if (ModbusTcpClientService.writeSingleCoil(1, true)) {
                        log.info("Run successful");
                        TcpClientService.sendProgressUpdate("启动","成功","1");
                    } else {
                        log.warn("Run failed");
                        TcpClientService.sendProgressUpdate("启动","失败","0");
                    }
                } else if (car_run_stop == 0) { //停止
                    if (ModbusTcpClientService.writeSingleCoil(1, false)) {
                        log.info("Stop successful");
                        TcpClientService.sendProgressUpdate("停止","成功","1");
                    } else {
                        log.warn("Stop failed");
                        TcpClientService.sendProgressUpdate("停止","失败","0");
                    }
                }
                break;
            case "RemoteCtrlAutomaticSwitch"://模式切换
                byte modelFlag = (byte)(JsonObject.getInt("points"));
                if (modelFlag == 1) { //自动
                    if (ModbusTcpClientService.writeSingleCoil(2, true)) {
                        log.info("Enter automatic mode");
                        TcpClientService.sendProgressUpdate("自动切换","成功","1");
                    } else {
                        log.warn("Entry automatic mode failed");
                        TcpClientService.sendProgressUpdate("自动切换","失败","0");
                    }
                } else if (modelFlag == 0) { //遥控
                    if (ModbusTcpClientService.writeSingleCoil(2, false)) {
                        log.info("Enter remote control mode");
                        TcpClientService.sendProgressUpdate("遥控切换","成功","1");
                    } else {
                        log.warn("Enter remote control mode failed");
                        TcpClientService.sendProgressUpdate("遥控切换", "失败","0");
                    }
                }
                break;
            case "Param"://参数设置
                SendToPlcController.paramToPlc(JsonObject);
                break;
            case "CloseRoute"://路径关闭
                if (ModbusTcpClientService.writeSingleCoil(3, true)) {
                    log.info("Path cancellation successful");
                    TcpClientService.sendProgressUpdate("路径取消","成功","1");
                } else {
                    log.warn("Path cancellation failed");
                    TcpClientService.sendProgressUpdate("路径取消","失败","0");
                }
                break;
            case "heartbeat":
                long currentTime = System.currentTimeMillis();
                // 检查是否超过1秒间隔
                if (currentTime - lastHeartbeatResponseTime >= HEARTBEAT_RESPONSE_INTERVAL) {
                    Operation_sign = true;
                    JSONObject progressInfo = new JSONObject();
                    progressInfo.put("action", "heartbeatResponse");
                    WebSocketServer.sendInfo(progressInfo.toString(), null);
                    log.info("收到心跳消息，进行响应");
                    lastHeartbeatResponseTime = currentTime; // 更新最后响应时间
                    Operation_sign = false;
                } else {
                    log.info("收到心跳消息，但距离上次响应不足1秒，忽略此次请求");
                }
                break;
            default:
                log.warn("未知的动作: " + action);
                break;
        }
    }
    public void sendMessage(String message) throws IOException {
        synchronized (this.session) {  // 添加同步块
            if (session != null && session.isOpen()) {
                try {
                    session.sendMessage(new TextMessage(message));
                } catch (IllegalStateException e) {
                    log.warn("发送消息时会话状态异常，尝试重新发送: " + e.getMessage());
                    // 可选：加入重试逻辑
                    if (session.isOpen()) {
                        session.sendMessage(new TextMessage(message));
                    }
                }
            }
        }
    }

    /**
     * 群发自定义消息
     */
    public static void sendInfo(String message, @PathParam("sid") String sid) {
        for (WebSocketServer item : webSocketSet) {
            try {
                if (sid == null || item.sid.equals(sid)) {
                    synchronized (item.session) {  // 添加同步控制
                        if (item.session.isOpen()) {
                            item.session.sendMessage(new TextMessage(message));
                        }
                    }
                }
            } catch (Exception e) {
                log.error("发送消息到客户端失败: " + e.getMessage(), e);
                // 关闭无效会话并从集合中移除
                try {
                    if (item.session.isOpen()) {
                        item.session.close();
                    }
                } catch (IOException ex) {
                    log.error("关闭会话失败", ex);
                }
                webSocketSet.remove(item);
            }
        }
    }

    // 创建支持中断的线程池
    public final ExecutorService executorService = new ThreadPoolExecutor(
            2,  // 核心线程数
            5,  // 最大线程数
            30, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(100),
            new ThreadFactory() {
                private final AtomicInteger counter = new AtomicInteger(0);
                @Override
                public Thread newThread(Runnable r) {
                    return new Thread(r, "plc-worker-" + counter.incrementAndGet());
                }
            },
            new ThreadPoolExecutor.CallerRunsPolicy()
    );

    public static synchronized int getOnlineCount() {
        return onlineCount;
    }

    public static synchronized void addOnlineCount() {
        onlineCount++;
    }

    public static synchronized void subOnlineCount() {
        onlineCount--;
    }

    public static CopyOnWriteArraySet<WebSocketServer> getWebSocketSet() {
        return webSocketSet;
    }

    // 获取JSONObject实例，使用后需要调用clear方法
    public static JSONObject getJsonObject() {
        return globalJsonObject;
    }

    // 清空JSONObject内容
    public static void clearJsonObject() {
        globalJsonObject = new JSONObject(); // 创建新实例
    }
}