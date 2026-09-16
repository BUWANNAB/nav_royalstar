package com.ant.robot.service;

import com.ant.robot.controller.RevdataController;
import com.ant.robot.model.domain.PriorityMessageQueue;
import com.ant.robot.websocket.WebSocketServer;
import jakarta.annotation.Resource;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONException;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Modbus TCP 客户端服务
 * 实现标准的 Modbus TCP 协议通信
 */
@Service
@Slf4j
public class TcpClientService {

    // 依赖的控制器
    @Resource
    private RevdataController revdataController;

    // 连接配置
    private static String serverAddress;
    private static int serverPort;
    private static final int RECONNECT_DELAY = 2000; // 重连间隔(毫秒)

    // 状态控制
    private static final AtomicInteger SendPath_Flag = new AtomicInteger(-1);
    private volatile boolean running = true;       // 服务运行状态
    private static volatile boolean isConnected = false;   // 连接状态

    // 网络资源
    private static Socket socket = null;
    private static OutputStream out = null;
    private static InputStream in = null;

    // 线程管理
    private Thread receiveThread;
    private Thread sendingThread;
    private Thread processingThread;
    private static final Object sendThreadLock = new Object();
    private static volatile boolean isMainSendingThreadPaused = false;
    // 消息队列
    private final PriorityMessageQueue priorityQueue = new PriorityMessageQueue();
    // Modbus 协议相关
    private static short transactionId = 0; // 事务ID计数器
    private static final byte UNIT_ID = 1; // Modbus 单元ID
    // 新增状态获取方法
    @Getter
    private static volatile int lastWriteCoilStatus = -1; // -1:未知 0:写线圈成功 其他:错误码
    @Getter
    private static volatile int lastWriteRegisterStatus = -1; // -1:未知 1:写寄存器成功 其他:错误码
    // 异常代码定义
    public static final byte ILLEGAL_FUNCTION = 0x01;
    public static final byte ILLEGAL_DATA_ADDRESS = 0x02;
    public static final byte ILLEGAL_DATA_VALUE = 0x03;
    public static final byte SLAVE_DEVICE_FAILURE = 0x04;
    // 异常监听器接口
    public interface ModbusExceptionListener {
        void onModbusException(byte functionCode, byte exceptionCode);
    }
    // 设置异常监听器
    @Setter
    private ModbusExceptionListener exceptionListener;

    /**
     * 初始化连接并启动所有线程
     */
    public void initializeConnection() throws IOException {
        connectToServer();
        log.info("Modbus TCP 连接初始化完成");
        startSendingThread();
        startReceivingThread();
        startProcessingThread();
    }
    /**
     * 停止所有线程并关闭连接
     */
    public void stopThreads() {
        running = false;
        try {
            if (sendingThread != null) sendingThread.join();
            if (receiveThread != null) receiveThread.join();
            if (processingThread != null) processingThread.join();
        } catch (InterruptedException e) {
            log.error("停止线程时出错: ", e);
            Thread.currentThread().interrupt();
        }
        closeConnection();
    }
    /**
     * 连接到Modbus服务器
     */
    private static void connectToServer() throws IOException {
        socket = new Socket(serverAddress, serverPort);
        out = socket.getOutputStream();
        in = socket.getInputStream();
        isConnected = true;
        log.info("已连接到Modbus服务器: {}:{}", serverAddress, serverPort);
    }
    /**
     * 关闭连接
     */
    private static void closeConnection() {
        try {
            if (out != null) out.close();
            if (in != null) in.close();
            if (socket != null && !socket.isClosed()) socket.close();
            isConnected = false;
            log.info("Modbus连接已关闭");
        } catch (IOException e) {
            log.error("关闭连接时出错: ", e);
        }
    }
    /**
     * 启动发送线程
     */
    private void startSendingThread() {
        sendingThread = new Thread(() -> {
            while (running) {
                synchronized (sendThreadLock) {
                    while (isMainSendingThreadPaused) {
                        try {
                            sendThreadLock.wait();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                }
                try {
                    if (!isConnected) {
                        reconnectToServer();
                        if (!isConnected) {
                            Thread.sleep(RECONNECT_DELAY);
                            continue;
                        }
                    }
                    // 创建读取50个保持寄存器的请求
                    byte[] readRequest = createReadHoldingAi(0, 50);
                    // 发送请求
                    sendModbusRequest(readRequest);
                    log.debug("已发送读取50个寄存器的请求");
                    // 设置查询间隔
                    Thread.sleep(1000); // 1秒查询一次

                } catch (InterruptedException e) {
                    log.error("发送线程被中断", e);
                    isConnected = false;
                    scheduleReconnection();
                    Thread.currentThread().interrupt();
                }
            }
        });
        sendingThread.setName("Modbus-Send-Thread");
        sendingThread.setDaemon(true);
        sendingThread.start();
    }
    /**
     * 启动接收线程
     */
    private void startReceivingThread() {
        receiveThread = new Thread(() -> {
            while (running) {
                try {
                    if (!isConnected) {
                        Thread.sleep(1000);
                        continue;
                    }
                    // 读取MBAP头(6字节)
                    byte[] header = new byte[6];
                    int bytesRead = in.read(header);
                    if (bytesRead == 6) {
                        // 计算数据长度
                        int length = ((header[4] & 0xFF) << 8) | (header[5] & 0xFF);
                        byte[] modbusData = new byte[6 + length]; // 总长度=头+数据
                        System.arraycopy(header, 0, modbusData, 0, 6);
                        // 读取剩余数据
                        bytesRead = in.read(modbusData, 6, length);
                        if (bytesRead == length) {
                            int priority = determinePriority(modbusData);
                            priorityQueue.putBinaryMessage(modbusData, priority);
                        }
                    }

                } catch (IOException e) {
                    log.error("接收数据时出错", e);
                    isConnected = false;
                    scheduleReconnection();
                } catch (InterruptedException e) {
                    log.error("接收线程被中断", e);
                    Thread.currentThread().interrupt();
                }
            }
        });
        receiveThread.setName("Modbus-Receive-Thread");
        receiveThread.start();
    }
    /**
     * 启动处理线程
     */
    private void startProcessingThread() {
        processingThread = new Thread(() -> {
            while (running) {
                try {
                    byte[] message = (byte[]) priorityQueue.takeMessage().getData();
                    processModbusResponse(message);
                } catch (InterruptedException e) {
                    log.error("处理线程被中断", e);
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    log.error("处理Modbus消息时出错", e);
                }
            }
        });
        processingThread.setName("Modbus-Process-Thread");
        processingThread.start();
    }
    /**
     * 处理Modbus响应（增强异常处理）
     */
    private void processModbusResponse(byte[] response) {
        try {
            if (response == null || response.length < 2) {
                log.error("无效的Modbus响应");
                return;
            }
            // 检查是否是异常响应（功能码最高位为1）
            if ((response[7] & 0x80) != 0) {
                handleExceptionResponse(response);
                return;
            }
            // 正常响应处理
            byte functionCode = response[7];
            switch (functionCode) {
                case 0x01: // 读取线圈状态
                    handleReadCoilsResponse(response);
                    break;
                case 0x03: // 读取保持寄存器
                    handleReadHoldingRegisters(response);
                    break;
                case 0x04: // 读取输入寄存器
                    handleReadHoldingAi(response);
                    break;
                case 0x06: // 写入单个寄存器
                    handleWriteSingleRegister(response);
                    break;
                case 0x10: // 写入多个寄存器
                    handleWriteMultipleRegistersResponse(response);
                    break;
                case 0x05: // 写单个线圈响应
                    handleWriteCoilResponse(response);
                    break;
                default:
                    log.warn("未处理的功能码: 0x{}", Integer.toHexString(functionCode & 0xFF));
            }
        } catch (Exception e) {
            log.error("处理Modbus响应时发生异常", e);
        }
    }
    /**
     * 处理Modbus异常响应
     */
    private void handleExceptionResponse(byte[] response) {
        if (response.length < 2) return;

        byte functionCode = (byte) (response[7] & 0x7F); // 获取原始功能码
        byte exceptionCode = response.length > 2 ? response[8] : 0;

        String errorMsg = getExceptionMessage(exceptionCode);
        log.error("Modbus异常 >> 功能码: 0x{}, 异常码: 0x{}, 描述: {}",
                Integer.toHexString(functionCode & 0xFF),
                Integer.toHexString(exceptionCode & 0xFF),
                errorMsg);

        // 更新状态
        switch (functionCode) {
            case 0x05: // 写线圈异常
                lastWriteCoilStatus = -exceptionCode;
                break;
            case 0x10: // 写寄存器异常
                lastWriteRegisterStatus = -exceptionCode;
                break;
        }

        // 通知监听器
        if (exceptionListener != null) {
            exceptionListener.onModbusException(functionCode, exceptionCode);
        }
    }
    /**
     * 获取异常描述信息
     */
    private String getExceptionMessage(byte exceptionCode) {
        return switch (exceptionCode) {
            case ILLEGAL_FUNCTION -> "设备不支持请求的功能";
            case ILLEGAL_DATA_ADDRESS -> "请求的数据地址无效";
            case ILLEGAL_DATA_VALUE -> "请求的数据值无效";
            case SLAVE_DEVICE_FAILURE -> "从站设备执行请求时发生故障";
            default -> "未知异常";
        };
    }
    /**
     * 处理写线圈响应
     */
    private void handleWriteCoilResponse(byte[] response) throws JSONException {
        if (response.length == 12) { // 成功响应长度
            int coilAddress = ((response[8] & 0xFF) << 8) | (response[9] & 0xFF);
            if (coilAddress == 0){
                boolean value = ((response[10] & 0xFF) == 0xFF);
                if (!value) { // 我们只关心写false操作
                    lastWriteCoilStatus = 0; // 标记写false成功
                    log.debug("线圈 {} 重置为false成功", coilAddress);
                }else {
                    lastWriteCoilStatus = -2; // 标记失败
                }
            }else if (coilAddress == 1){
                sendProgressUpdate("Run_Stop","Successful","1");
                log.info("Run_Stop Successful");
            } else if (coilAddress == 2) {
                sendProgressUpdate("Model","Successful","1");
                log.info("Model Successful");
            } else if (coilAddress == 3) {
                sendProgressUpdate("Path_Delete","Successful","1");
                log.info("Path_Delete Successful");
            } else if (coilAddress == 4) {
                boolean value = ((response[10] & 0xFF) == 0xFF);
                if (!value) {
                    sendProgressUpdate("Param_Set","Fail","0");
                    log.info("Param_Set Fail");
                }
            }

        }
    }

    /**
     * 处理写入多个寄存器响应
     */
    public void handleWriteMultipleRegistersResponse(byte[] response) {
        // 验证写入是否成功
        if (response.length >= 12) {
            int startAddress = ((response[8] & 0xFF) << 8) | (response[9] & 0xFF);
            int quantity = ((response[10] & 0xFF) << 8) | (response[11] & 0xFF);
            log.info("成功写入{}个寄存器到地址{}", quantity, startAddress);
            lastWriteRegisterStatus = 1;
        }
    }

    /**
     * 处理写入单个寄存器响应
     */
    public void handleWriteSingleRegister(byte[] response) {
        if (response.length < 12) {
            log.error("无效的写入响应长度");
            return;
        }
        int address = ((response[8] & 0xFF) << 8) | (response[9] & 0xFF);
        int value = ((response[10] & 0xFF) << 8) | (response[11] & 0xFF);
        SendPath_Flag.set(value);
        log.info("寄存器[{}]写入成功, 值: {}", address, value);
    }
    /**
     * 发送Modbus请求
     */
    public static void sendModbusRequest(byte[] request) {
        try {
            if (!isConnected) {
                reconnectToServer();
                if (!isConnected) {
                    log.warn("发送失败: 未连接");
                    return;
                }
            }

            out.write(request);
            out.flush();
        } catch (IOException e) {
            log.error("发送Modbus请求时出错", e);
            isConnected = false;
            scheduleReconnection();
        }
    }

    /**
     * 处理读取保持寄存器响应
     */
    public void handleReadHoldingRegisters(byte[] response) throws JSONException {
        int byteCount = response[8] & 0xFF;
        if (response.length < 9 + byteCount) {
            log.error("无效的读取响应长度");
            return;
        }
        byte[] registerData = Arrays.copyOfRange(response, 9, 9 + byteCount);
        revdataController.parseAndProcessData(registerData);
    }
    /**
     * 处理读取输入存器响应
     */
    public void handleReadHoldingAi(byte[] response) throws JSONException {
        int byteCount = response[8] & 0xFF;
        if (response.length < 9 + byteCount) {
            log.error("无效的读取响应长度");
            return;
        }
        byte[] registerData = Arrays.copyOfRange(response, 9, 9 + byteCount);
        revdataController.parseAndProcessData(registerData);
    }
    /**
     * 处理读取线圈响应
     */
    public void handleReadCoilsResponse(byte[] response) throws JSONException {
        if (response.length < 9) {
            log.error("无效的线圈响应长度");
            return;
        }
        log.info("Read Coils Response {}",response);
        int byteCount = response[8] & 0xFF;
        if (byteCount < 1) return;
        // 检查第一个线圈状态(bit 0)
        boolean coilStatus = (response[9] & 0x01) != 0;
        if (coilStatus) {
            SendPath_Flag.set(1); // 设置标志表示PLC已接收
            log.info("检测到线圈状态为True，设置接收标志");
            return;
        }
        coilStatus = (response[9] & 0x08) == 0;
        if (coilStatus) {
            sendProgressUpdate("Param_Set","Successful","1");
            log.info("参数设置成功");
        }
    }
    /**
     * 创建读取输入寄存器请求
     */
    public byte[] createReadHoldingAi(int startingAddress, int quantity) {
        byte[] request = new byte[12];

        // MBAP头
        request[0] = (byte) (transactionId >> 8);
        request[1] = (byte) (transactionId & 0xFF);
        transactionId++;

        request[2] = 0; // 协议ID高字节
        request[3] = 0; // 协议ID低字节

        request[4] = 0; // 长度高字节(后面设置)
        request[5] = 6; // 长度低字节(固定6)

        // Modbus PDU
        request[6] = UNIT_ID; // 单元ID
        request[7] = 0x04;    // 功能码: 读取保持寄存器

        request[8] = (byte) (startingAddress >> 8); // 起始地址高字节
        request[9] = (byte) (startingAddress & 0xFF); // 起始地址低字节

        request[10] = (byte) (quantity >> 8); // 寄存器数量高字节
        request[11] = (byte) (quantity & 0xFF); // 寄存器数量低字节

        return request;
    }

    /**
     * 创建Modbus读取线圈请求(功能码0x01)
     * @param startAddress 起始线圈地址
     * @param quantity 要读取的线圈数量
     */
    public static byte[] createReadCoilsRequest(int startAddress, int quantity) {
        byte[] request = new byte[12];

        // MBAP头
        request[0] = (byte) (transactionId >> 8);
        request[1] = (byte) (transactionId & 0xFF);
        transactionId++;

        request[2] = 0; // 协议ID高字节
        request[3] = 0; // 协议ID低字节

        request[4] = 0; // 长度高字节
        request[5] = 6; // 长度低字节

        // Modbus PDU
        request[6] = UNIT_ID; // 单元ID
        request[7] = 0x01;    // 功能码: 读取线圈

        request[8] = (byte) (startAddress >> 8); // 起始地址高字节
        request[9] = (byte) (startAddress & 0xFF); // 起始地址低字节

        request[10] = (byte) (quantity >> 8); // 线圈数量高字节
        request[11] = (byte) (quantity & 0xFF); // 线圈数量低字节

        return request;
    }
    /**
     * 创建Modbus写单个线圈请求(功能码0x05)
     * @param coilAddress 线圈地址
     * @param value 要设置的值(true/false)
     */
    public static byte[] createWriteSingleCoilRequest(int coilAddress, boolean value) {
        byte[] request = new byte[12];

        // MBAP头
        request[0] = (byte) (transactionId >> 8);
        request[1] = (byte) (transactionId & 0xFF);
        transactionId++;

        request[2] = 0; // 协议ID高字节
        request[3] = 0; // 协议ID低字节

        request[4] = 0; // 长度高字节
        request[5] = 6; // 长度低字节

        // Modbus PDU
        request[6] = UNIT_ID; // 单元ID
        request[7] = 0x05;    // 功能码: 写单个线圈

        request[8] = (byte) (coilAddress >> 8); // 线圈地址高字节
        request[9] = (byte) (coilAddress & 0xFF); // 线圈地址低字节

        // 线圈值(0xFF00表示ON, 0x0000表示OFF)
        request[10] = value ? (byte) 0xFF : 0x00;
        request[11] = 0x00;

        return request;
    }
    /**
     * 创建Modbus写入多个寄存器请求(功能码0x10)
     */
    public static byte[] createWriteMultipleRegistersRequest(int startAddress, int quantity, byte[] values) {
        int byteCount = values.length;
        byte[] request = new byte[13 + byteCount];

        // MBAP头
        request[0] = (byte) (transactionId >> 8);
        request[1] = (byte) (transactionId & 0xFF);
        transactionId++;

        request[2] = 0; // 协议ID高字节
        request[3] = 0; // 协议ID低字节

        request[4] = (byte) ((7 + byteCount) >> 8); // 长度高字节
        request[5] = (byte) ((7 + byteCount) & 0xFF); // 长度低字节

        // Modbus PDU
        request[6] = UNIT_ID; // 单元ID
        request[7] = 0x10;    // 功能码: 写多个寄存器

        request[8] = (byte) (startAddress >> 8); // 起始地址高字节
        request[9] = (byte) (startAddress & 0xFF); // 起始地址低字节

        request[10] = (byte) (quantity >> 8); // 寄存器数量高字节
        request[11] = (byte) (quantity & 0xFF); // 寄存器数量低字节

        request[12] = (byte) byteCount; // 字节计数

        System.arraycopy(values, 0, request, 13, byteCount); // 寄存器值

        return request;
    }
    /**
     * 创建写入单个寄存器请求
     */
    public byte[] createWriteSingleRegisterRequest(int address, int value) {
        byte[] request = new byte[12];

        // MBAP头
        request[0] = (byte) (transactionId >> 8);
        request[1] = (byte) (transactionId & 0xFF);
        transactionId++;

        request[2] = 0; // 协议ID高字节
        request[3] = 0; // 协议ID低字节

        request[4] = 0; // 长度高字节
        request[5] = 6; // 长度低字节

        // Modbus PDU
        request[6] = UNIT_ID; // 单元ID
        request[7] = 0x06;    // 功能码: 写入单个寄存器

        request[8] = (byte) (address >> 8); // 寄存器地址高字节
        request[9] = (byte) (address & 0xFF); // 寄存器地址低字节

        request[10] = (byte) (value >> 8); // 写入值高字节
        request[11] = (byte) (value & 0xFF); // 写入值低字节

        return request;
    }


    /**
     * 设置服务器地址
     */
    @Value("${robot.plc.host:192.168.8.30}")
    public void setServerAddress(String serverAddress) {
        TcpClientService.serverAddress = serverAddress;
    }

    @Value("${robot.plc.port:502}")
    public void setServerPort(int serverPort) {
        TcpClientService.serverPort = serverPort;
    }
    /**
     * 暂停主发送线程
     */
    public static void pauseMainSendingThread() {
        synchronized (sendThreadLock) {
            isMainSendingThreadPaused = true;
        }
    }

    /**
     * 恢复主发送线程
     */
    public static void resumeMainSendingThread() {
        synchronized (sendThreadLock) {
            isMainSendingThreadPaused = false;
            sendThreadLock.notify();
        }
    }
    /**
     * 重置状态
     */
    public synchronized void resetState() {
        SendPath_Flag.set(-1);
        log.info("Modbus TCP连接状态已重置");
    }

    // 重置状态方法
    public void resetWriteStatus() {
        lastWriteCoilStatus = -1;
        lastWriteRegisterStatus = -1;
    }
    /**
     * 获取发送路径标志
     */
    public static int getSendPathFlag() {
        return SendPath_Flag.get();
    }
    /**
     * 设置发送路径标志
     */
    public static void setSendPathFlag(int value) {
        SendPath_Flag.set(value);
    }
    /**
     * 确定消息优先级
     */
    private int determinePriority(byte[] message) {
        if (message == null || message.length < 8) return 5;

        switch (message[7]) {
            case 0x10: return 0; // 写入单个寄存器 - 最高优先级
            case 0x01: return 1; // 读取保持寄存器
            case 0x04: return 2; // 写入多个寄存器
            default: return 5;    // 其他功能码 - 最低优先级
        }
    }
    /**
     * 重连服务器
     */
    private static void reconnectToServer() {
        closeConnection();
        try {
            Thread.sleep(RECONNECT_DELAY);
            connectToServer();
        } catch (IOException e) {
            log.error("连接服务器失败", e);
        } catch (InterruptedException e) {
            log.error("重连线程被中断", e);
            Thread.currentThread().interrupt();
        }
    }
    /**
     * 安排重连
     */
    private static void scheduleReconnection() {
        new Thread(() -> {
            try {
                Thread.sleep(RECONNECT_DELAY);
                reconnectToServer();
            } catch (InterruptedException e) {
                log.error("重连线程被中断", e);
                Thread.currentThread().interrupt();
            }
        }).start();
    }
    /**
     * 发送进度更新到WebSocket
     */
    public static void sendProgressUpdate(String request, String outcome,String status) throws JSONException {
        JSONObject progressInfo = new JSONObject();
        progressInfo.put("action", "feedback");
        progressInfo.put("request", request)
                    .put("status", status)
                    .put("outcome", outcome);
        WebSocketServer.sendInfo(progressInfo.toString(), null);
    }
}
