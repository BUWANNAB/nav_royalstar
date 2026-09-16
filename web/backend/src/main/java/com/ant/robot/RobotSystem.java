package com.ant.robot;

import com.ant.robot.common.constants.SystemConstant;
import com.ant.robot.controller.DatabaseMigrator;
import com.ant.robot.event.ConnectionEvent;
import com.ant.robot.mapper.SystemConfigMapper;
import com.ant.robot.model.domain.Station;
import com.ant.robot.model.domain.SystemConfig;
import com.ant.robot.service.ModbusTcpClientService;
import com.ant.robot.service.MqttClientService;
import com.ant.robot.service.StationService;
import com.ant.robot.service.ROS2CommunicationService;
import com.ant.robot.model.domain.Param;
import com.ant.robot.mapper.ParamMapper;
import com.ant.robot.service.TcpClientService;
import com.ant.robot.websocket.WebSocketServer;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import java.io.BufferedReader; 
import java.io.InputStreamReader; 

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@SpringBootApplication
@EnableScheduling
@EnableAsync
@EnableTransactionManagement
@MapperScan("com.ant.robot.mapper")
public class RobotSystem implements CommandLineRunner, ApplicationContextAware {

    // 系统启动时间戳
    public static final AtomicLong systemStartTimestamp = new AtomicLong(0);
    private static ApplicationContext context;

    // 连接状态标记
    private final AtomicBoolean tcpConnected = new AtomicBoolean(false);
    private final AtomicBoolean modbusConnected = new AtomicBoolean(false);
    private final AtomicBoolean mqttConnected = new AtomicBoolean(false);
    private final AtomicBoolean rosWsConnected = new AtomicBoolean(false);

    // 线程池用于异步初始化连接
    private final ExecutorService connectionExecutor = Executors.newCachedThreadPool();

    // 服务注入
    @Resource
    private WebSocketServer webSocketServer;
    @Resource
    private StationService stationService;
    @Resource
    private SystemConfigMapper systemConfigMapper;
    @Resource
    private TcpClientService tcpClientService;
    @Resource
    private ModbusTcpClientService modbusTcpClientService;
    @Resource
    private MqttClientService mqttClientService;
    @Resource
    private ApplicationEventPublisher eventPublisher;
    @Resource
    private ParamMapper paramMapper;
    @Resource
    private ROS2CommunicationService ros2CommunicationService; // 需要确保这个服务存在
    @Value("${server.port:8088}")
    private int serverPort;
    @Value("${robot.browser.auto-open:false}")
    private boolean autoOpenBrowser;
    @Value("${robot.plc.java-client-enabled:false}")
    private boolean javaPlcClientEnabled;

    public static void main(String[] args) {
        SpringApplication.run(RobotSystem.class, args);
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) {
        context = applicationContext;
    }

    public static boolean isSystemReady() {
        return context != null;
    }

    public static long getSystemStartTimestamp() {
        return systemStartTimestamp.get();
    }

    @Override
    public void run(String... args) throws Exception {
        // 记录系统启动时间
        systemStartTimestamp.set(System.currentTimeMillis());

        // 初始化基础配置
        init();

        // 异步启动所有连接服务
        startConnectionsAsync();

        // 注册关闭钩子
        registerShutdownHook();

        log.info("系统主线程启动完成，连接服务正在异步初始化...");
        
        if (autoOpenBrowser) {
            openBrowser();
        }
    }
    
    /**
     * 自动打开浏览器访问系统
     */
    private void openBrowser() {
        try {
            String url = "http://localhost:" + serverPort;
            
            // 获取操作系统类型
            String os = System.getProperty("os.name").toLowerCase();
            
             // 根据不同操作系统选择打开浏览器的方式
            if (os.contains("win")) {
                // Windows系统
                Runtime.getRuntime().exec("rundll32 url.dll,FileProtocolHandler " + url);
            } else if (os.contains("mac")) {
                // macOS系统
                Runtime.getRuntime().exec("open " + url);
            } else if (os.contains("nix") || os.contains("nux")) {
                Runtime.getRuntime().exec(new String[]{"xdg-open", url});
            } else {
                log.warn("不支持的操作系统，无法自动打开浏览器: {}", os);
            }
            
            log.info("已自动打开浏览器访问: {}", url);
        } catch (Exception e) {
            log.error("自动打开浏览器失败: {}", e.getMessage());
        }
    }
    /**
     * 异步启动所有连接服务
     */
    private void startConnectionsAsync() {
        // TCP连接
        /*connectionExecutor.execute(() -> {
            startServiceWithRetry(
                    "TCP",
                    () -> {
                        try {
                            tcpClientService.initializeConnection();
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    },
                    tcpConnected,
                    3,
                    5
            );
        });*/

        // ROS2 底盘模式下由 ros2plc 独占有人模块，避免两个 Modbus 主站争抢同一连接。
        if (javaPlcClientEnabled) {
            connectionExecutor.execute(() -> {
                startServiceWithRetry(
                        "Modbus TCP",
                        () -> modbusTcpClientService.initializeConnection(),
                        modbusConnected,
                        3,
                        5
                );
            });
        } else {
            log.info("Java PLC客户端已禁用，PLC连接交由ros2plc管理");
        }

        // MQTT连接
        connectionExecutor.execute(() -> {
            startServiceWithRetry(
                    "MQTT",
                    () -> {
                        try {
                            mqttClientService.initializeConnection();
                        } catch (MqttException e) {
                            throw new RuntimeException(e);
                        }
                        mqttClientService.generateAndSendSessionKey();
                        mqttClientService.startSubscribing();
                    },
                    mqttConnected,
                    3,
                    5
            );
        });
    }

    /**
     * 带重试机制的服务启动
     */
    private void startServiceWithRetry(String serviceName, Runnable serviceInit,
                                       AtomicBoolean statusFlag,
                                       int maxRetries, int retryIntervalSec) {
        int retryCount = 0;
        while (retryCount < maxRetries && !statusFlag.get()) {
            try {
                serviceInit.run();
                statusFlag.set(true);
                log.info("{}服务初始化成功", serviceName);
                eventPublisher.publishEvent(new ConnectionEvent(serviceName, true));
                return;
            } catch (Exception e) {
                retryCount++;
                log.warn("{}服务初始化失败({}/{}): {}",
                        serviceName, retryCount, maxRetries, e.getMessage());

                if (retryCount < maxRetries) {
                    try {
                        TimeUnit.SECONDS.sleep(retryIntervalSec);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }

        if (!statusFlag.get()) {
            log.error("{}服务初始化最终失败，已达到最大重试次数{}", serviceName, maxRetries);
            eventPublisher.publishEvent(new ConnectionEvent(serviceName, false));
        }
    }

    /**
     * 注册关闭钩子
     */
    private void registerShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("\n正在关闭系统...");

            // 关闭连接服务
            shutdownConnections();

            // 停止数据库迁移
            DatabaseMigrator migrator = new DatabaseMigrator();
            migrator.stopAllMigrations();

            log.info("系统已安全关闭");
        }));
    }

    /**
     * 关闭所有连接
     */
    private void shutdownConnections() {
        log.info("正在关闭网络连接...");

        try {
            /*if (tcpConnected.get()) {
                tcpClientService.shutdown();
            }*/
            if (modbusConnected.get()) {
                modbusTcpClientService.shutdown();
            }
            if (mqttConnected.get()) {
                mqttClientService.disconnect();
            }
        } catch (Exception e) {
            log.error("关闭连接时发生异常: ", e);
        }
    }

    /**
     * 初始化基础配置
     */
    public void init() {
        // 从数据库中读取初始化站点的信息
        QueryWrapper<SystemConfig> systemConfigQueryWrapper = new QueryWrapper<>();
        systemConfigQueryWrapper.eq("configKey", SystemConstant.INIT_POSE);
    
        SystemConfig systemConfig = systemConfigMapper.selectOne(systemConfigQueryWrapper);
        if (systemConfig == null) {
            log.warn("未找到初始位置配置");
            return;
        }
    
        QueryWrapper<Param> paramQueryWrapper = new QueryWrapper<>();
        Param param = paramMapper.selectOne(paramQueryWrapper);
        
        Long stationId = Long.valueOf(param.getBoot_point());
        Station station = stationService.getById(stationId);
        
        if (station != null) {
            // 新开线程异步检测节点状态和发布初始位姿，避免阻塞主程序
            connectionExecutor.execute(() -> {
                try {
                    log.info("开始异步检测/lidar_localization节点状态...");
                    
                    // 检测lidar_localization节点是否存在
                    boolean nodeExists = isLidarLocalizationNodeRunning();
                    
                    if (nodeExists) {
                        log.info("/lidar_localization节点存在，等待5秒后发布初始位姿...");
                        
                        // 延时5秒
                        TimeUnit.SECONDS.sleep(5);
                        
                        // 通过ROS2CommunicationService发布初始位姿
                        boolean publishSuccess = ros2CommunicationService.publishInitialPose(
                            Double.parseDouble(station.getPositionX()),
                            Double.parseDouble(station.getPositionY()),
                            Double.parseDouble(station.getPositionZ()),
                            Double.parseDouble(station.getOrientationX()),
                            Double.parseDouble(station.getOrientationY()),
                            Double.parseDouble(station.getOrientationZ()),
                            Double.parseDouble(station.getOrientationW())
                        );
                        
                        if (publishSuccess) {
                            log.info("初始位姿发布成功 - 站点: {}, X: {}, Y: {}, Z: {}", 
                                    station.getStationName(),
                                    station.getPositionX(),
                                    station.getPositionY(),
                                    station.getPositionZ());
                        } else {
                            log.error("初始位姿发布失败 - 站点: {}", station.getStationName());
                        }
                        
                    } else {
                        log.warn("/lidar_localization节点不存在，跳过初始位姿发布");
                    }
                    
                } catch (InterruptedException e) {
                    log.error("异步检测线程被中断", e);
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    log.error("异步检测节点状态时发生异常", e);
                }
            });
            
            log.info("已启动异步线程检测节点状态，主程序继续运行...");
            
        } else {
            log.error("未找到ID为{}的站点信息", stationId);
        }
        
        String OS = System.getProperty("os.name").toLowerCase();
        if (OS.contains("win")) {
            log.info("当前系统为Windows");
        }
        System.out.println("================================== 系统初始化完成 ==================================");
    }
    
    /**
     * 检测lidar_localization节点是否正在运行
     */
    private boolean isLidarLocalizationNodeRunning() {
        try {
            // 方法1: 使用ros2 node list命令查找节点
            String command = "source ~/blueant_nav_ws/install/setup.bash && ros2 node list | grep -w '/lidar_localization'";
            
            ProcessBuilder pb = new ProcessBuilder("/bin/bash", "-c", command);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line.trim());
                }
            }
            
            int exitCode = process.waitFor(5, TimeUnit.SECONDS) ? process.exitValue() : -1;
            
            // 如果命令执行成功且输出中包含目标节点名称，则节点存在
            if (exitCode == 0 && output.toString().contains("/lidar_localization")) {
                return true;
            }
            
            // 方法2: 备用方法 - 使用ps命令查找相关进程
            String backupCommand = "ps aux | grep lidar_localization | grep -v grep";
            ProcessBuilder backupPb = new ProcessBuilder("/bin/bash", "-c", backupCommand);
            Process backupProcess = backupPb.start();
            
            StringBuilder backupOutput = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(backupProcess.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    backupOutput.append(line);
                }
            }
            
            int backupExitCode = backupProcess.waitFor(3, TimeUnit.SECONDS) ? 
                               backupProcess.exitValue() : -1;
            
            // 如果找到相关进程，则节点可能存在
            if (backupExitCode == 0 && !backupOutput.toString().trim().isEmpty()) {
                log.debug("通过进程检测发现lidar_localization相关进程");
                return true;
            }
            
            return false;
            
        } catch (Exception e) {
            log.error("检测节点状态时发生异常", e);
            return false;
        }
    }

    @PreDestroy
    public void cleanup() {
        log.info("正在关闭连接线程池...");
        connectionExecutor.shutdownNow();
    }

    /**
     * 监听连接状态变化
     */
    @EventListener
    public void handleConnectionEvent(ConnectionEvent event) {
        log.info("连接状态变化 - 服务: {}, 状态: {}",
                event.getServiceName(),
                event.isConnected() ? "已连接" : "连接失败");

        // 这里可以添加更复杂的连接状态处理逻辑
    }
}
