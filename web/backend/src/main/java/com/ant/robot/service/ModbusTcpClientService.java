package com.ant.robot.service;

import com.ant.robot.controller.RevdataController;
import com.ant.robot.utils.ModbusUtils;
import com.ghgande.j2mod.modbus.io.ModbusTCPTransaction;
import com.ghgande.j2mod.modbus.msg.*;
import com.ghgande.j2mod.modbus.net.TCPMasterConnection;
import com.ghgande.j2mod.modbus.util.BitVector;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.InetAddress;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

@Service
@Slf4j
public class ModbusTcpClientService {

    @Value("${robot.plc.host:192.168.8.30}")
    private String host;

    @Value("${robot.plc.port:502}")
    private int port;
    private static int unitId = 1;
    private int timeout = 2000;

    private static TCPMasterConnection connection;
    // Modbus 协议相关
    private volatile boolean running = true;
    private Thread receiveThread;
    private Thread reconnectThread;
    public static final AtomicInteger connectionStatus = new AtomicInteger(0); // 0:断开 1:连接中 2:已连接

    private static final int RECONNECT_INTERVAL = 2000;    // 重连间隔(ms)
    private static final int POLLING_INTERVAL = 10;      // 轮询间隔(ms)
    // 定义优先级常量
    public static final int PATH_PRIORITY = 2;           // 路径
    public static final int OTHER_PRIORITY = 1;          // 其余
    public static final int STATUS_QUERY_PRIORITY = 0;   // 状态查询 - 最低优先级
    //更新时间戳
    private static volatile long lastResponseTime = 0;
    // 使用可重入锁替代原有的两个锁
    private static final ReentrantLock priorityLock = new ReentrantLock(true); // 公平锁
    private static final ThreadLocal<Integer> currentThreadPriority = new ThreadLocal<>();

    public static int Idle_timer;//线程闲置计时

    @Resource
    private  RevdataController revdataController;

    /**
     * 初始化连接并启动线程
     */
    public synchronized void initializeConnection() {
        if (connectionStatus.get() > 0) {
            log.warn("The connection exists");
            return;
        }
        connectionStatus.set(1); // 标记为连接中
        startReconnectThread();
        startTaskprocessingThread();
        log.info("1111");
    }

    public void shutdown() {
        running = false;

        // 中断并等待重连线程
        if (reconnectThread != null) {
            reconnectThread.interrupt();
            try {
                reconnectThread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        // 中断并等待接收线程
        if (receiveThread != null) {
            receiveThread.interrupt();
            try {
                receiveThread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        // 关闭Modbus连接
        closeConnection();

        log.info("ModbusTcpClientService shutdown complete");
    }

    /****************************************线程******************************************************/
    /**
     * 启动重连线程
     */
    private void startReconnectThread() {
        reconnectThread = new Thread(() -> {
            while (running) {
                try {
                    if (connectionStatus.get() != 2) { // 非已连接状态
                        connectToServer();
                        TcpClientService.sendProgressUpdate("连接状态","连接断开，尝试重新连接","0");
                    }
                    Thread.sleep(RECONNECT_INTERVAL);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    log.error("Reconnect Error", e);
                }
            }
        });
        reconnectThread.setName("Modbus-Reconnect-Thread");
        reconnectThread.setDaemon(true);
        reconnectThread.start();
    }

    /**
     * 任务处理线程
     */
    private void startTaskprocessingThread() {
        receiveThread = new Thread(() -> {
            while (running) {
                try {
                    if (connectionStatus.get() == 2) { // 已连接状态
                        long currentTime = System.currentTimeMillis();
                        // 检查是否超过间隔
                        if (currentTime - lastResponseTime >= 500) {
                            ModbusTcpClientService.acquirePriorityLock(ModbusTcpClientService.STATUS_QUERY_PRIORITY);
                            try {
                                readStatus();
                                lastResponseTime = currentTime;
                            } finally {
                                ModbusTcpClientService.releasePriorityLock();
                            }
                        }
                    }
                    Thread.sleep(10); // 添加短暂休眠避免CPU占用过高
                } catch (Exception e) {
                    log.error("Task processing Thread Error", e);
                    connectionStatus.set(0); // 标记为断开
                } finally {
                    ModbusTcpClientService.releasePriorityLock();
                }
            }
        });
        receiveThread.setName("Modbus-Task-processing-Thread");
        receiveThread.setDaemon(true);
        receiveThread.start();
    }

    /**
     * 定期读取输入寄存器（自动任务）
     */
    public void readStatus() {
        try {
            byte[] values = readInputRegisters(0, 50);
            if (values.length > 0) {
                // 处理读取到的数据
                revdataController.parseAndProcessData(values);
            }
        } catch (Exception e) {
            log.error("自动读取输入寄存器异常", e);
        }
    }

    /***************************************写操作*************************************************/
    /**
     * 写入单个线圈
     * @param coilAddress 线圈地址
     * @param value 要设置的值
     * @return 是否成功
     */
    public static boolean writeSingleCoil(int coilAddress, boolean value) {
        try {
            WriteCoilRequest request = new WriteCoilRequest(coilAddress, value);
            request.setUnitID(unitId);

            ModbusTCPTransaction trans = new ModbusTCPTransaction(connection);
            trans.setRequest(request);
            trans.execute();

            WriteCoilResponse response = (WriteCoilResponse) trans.getResponse();
            return response != null && response.getCoil() == value;
        } catch (Exception e) {
            log.error("写入线圈失败", e);
            connectionStatus.set(0);
            return false;
        }
    }

    /**
     * 写入多个寄存器
     * @param startAddress 起始地址
     * @param registers 寄存器值
     * @return 是否成功
     */
    public static boolean writeMultipleRegisters(int startAddress, int[] registers) {
        try {
            WriteMultipleRegistersRequest request = new WriteMultipleRegistersRequest(
                    startAddress,
                    ModbusUtils.toRegisterArray(registers)
            );
            request.setUnitID(unitId);

            ModbusTCPTransaction trans = new ModbusTCPTransaction(connection);
            trans.setRequest(request);
            trans.execute();

            return trans.getResponse() != null;
        } catch (Exception e) {
            log.error("写入多个寄存器失败", e);
            connectionStatus.set(0);
            return false;
        }
    }

    /***************************************读操作*************************************************/
    /**
     * 读取输入寄存器
     * @param startAddress 起始地址
     * @param count 读取数量
     * @return 读取到的寄存器值数组
     */
    public static byte[] readInputRegisters(int startAddress, int count) {
        try {
            // 创建读取输入寄存器请求
            ReadInputRegistersRequest request = new ReadInputRegistersRequest(startAddress, count);
            request.setUnitID(unitId);

            // 创建事务并执行
            ModbusTCPTransaction trans = new ModbusTCPTransaction(connection);
            trans.setRequest(request);
            trans.execute();

            // 获取响应
            ReadInputRegistersResponse response = (ReadInputRegistersResponse) trans.getResponse();
            if (response == null) {
                log.error("读取输入寄存器无响应");
                return new byte[0];
            }

            // 转换为int数组返回
            return ModbusUtils.toByteArray(response.getRegisters());
        } catch (Exception e) {
            log.error("读取输入寄存器异常 - 地址: {}, 数量: {}", startAddress, count, e);
            connectionStatus.set(0);
            return new byte[0];
        }
    }
    /**
     * 读取单个输入寄存器
     * @param address 寄存器地址
     * @return 寄存器值，读取失败返回-1
     */
    public int readInputRegister(int address) {
        byte[] values = readInputRegisters(address, 1);
        return values.length > 0 ? values[0] : -1;
    }
    /**
     * 读取线圈状态
     * @param coilAddress 起始地址
     * @param count 读取数量
     * @return 读取结果
     */
    public static boolean[] readCoils(int coilAddress, int count) {
        try {
            ReadCoilsRequest request = new ReadCoilsRequest(coilAddress, count);
            request.setUnitID(unitId);

            ModbusTCPTransaction trans = new ModbusTCPTransaction(connection);
            trans.setRequest(request);
            trans.execute();

            ReadCoilsResponse response = (ReadCoilsResponse) trans.getResponse();
            if (response != null) {
                BitVector coils = response.getCoils();
                boolean[] result = new boolean[coils.size()];
                for (int i = 0; i < coils.size(); i++) {
                    result[i] = coils.getBit(i); // 获取每一位的布尔值
                }
                return result;
            } else {
                return new boolean[0];
            }
        } catch (Exception e) {
            log.error("读取线圈失败", e);
            connectionStatus.set(0);
            return new boolean[0];
        }
    }
    /**
     * 读取保持寄存器
     * @param startAddress 起始地址
     * @param count 读取数量
     * @return 寄存器值
     */
    public int[] readHoldingRegisters(int startAddress, int count) {
        try {
            ReadMultipleRegistersRequest request = new ReadMultipleRegistersRequest(startAddress, count);
            request.setUnitID(unitId);

            ModbusTCPTransaction trans = new ModbusTCPTransaction(connection);
            trans.setRequest(request);
            trans.execute();

            ReadMultipleRegistersResponse response = (ReadMultipleRegistersResponse) trans.getResponse();
            return response != null ? ModbusUtils.toIntArray(response.getRegisters()) : new int[0];
        } catch (Exception e) {
            log.error("读取保持寄存器失败", e);
            connectionStatus.set(0);
            return new int[0];
        }
    }

    /*******************************************辅助函数*******************************************/
    /**
     * 连接服务器
     */
    private synchronized void connectToServer() throws JSONException {
        if (connectionStatus.get() == 2) return;
        try {
            closeConnection(); // 先关闭现有连接
            InetAddress addr = InetAddress.getByName(host);
            connection = new TCPMasterConnection(addr);
            connection.setPort(port);
            connection.setTimeout(timeout);
            connection.connect();
            connectionStatus.set(2); // 标记为已连接
            log.info("Modbus TCP连接已建立: {}:{}", host, port);
            TcpClientService.sendProgressUpdate("连接状态","已连接","1");
        } catch (Exception e) {
            connectionStatus.set(0); // 标记为断开
            log.error("连接服务器失败: {}:{}, {}", host, port, e.getMessage());
            TcpClientService.sendProgressUpdate("连接状态","连接失败，等待重试","0");
        }
    }
    /**
     * 关闭连接
     */
    public synchronized void closeConnection() {
        try {
            if (connection != null) {
                connection.close();
                connection = null;
            }
            connectionStatus.set(0); // 标记为断开
        } catch (Exception e) {
            log.error("关闭连接时发生异常", e);
        }
    }
    /******************************************线程锁*****************************************************/
    /**
     * 释放锁并清理线程优先级
     */
    public static void releasePriorityLock() {
        if (priorityLock.isHeldByCurrentThread()) {
            currentThreadPriority.remove();
            priorityLock.unlock();
        }
    }

    /**
     * 获取优先级锁
     * @param priority 优先级 (PATH_PRIORITY, OTHER_PRIORITY, STATUS_QUERY_PRIORITY)
     */
    public static void acquirePriorityLock(int priority) {
        priorityLock.lock();
        try {
            currentThreadPriority.set(priority);
        } finally {
            if (currentThreadPriority.get() != priority) {
                priorityLock.unlock();
            }
        }
    }


}
