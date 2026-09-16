package com.ant.robot.controller;

import com.ant.robot.model.domain.PointInfo;
import com.ant.robot.service.ModbusTcpClientService;
import com.ant.robot.service.TcpClientService;
import com.ant.robot.websocket.WebSocketServer;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.ByteBuffer;

@Slf4j
@Component
public class SendToPlcController {

    static int pointsendnum = 1;//当前发送点个数
    private static final int MAX_RETRIES = 3;             // 最大重试次数
    private static final int RETRY_INTERVAL_MS = 500;     // 重试间隔(ms)
    private static final int COIL_CHECK_INTERVAL = 200;    // 线圈检查间隔(ms)
    private static final int MAX_COIL_CHECKS = 5;         // 最大检查次数
    private static final int confirmCoilAddress = 0; // 确认线圈地址
    private static final int dataStartAddress = 0;   // 数据起始寄存器地址
    private static final int PATH_POINT_FIELD_COUNT = 9; // 每个路径点的协议字段数
    private static final int PATH_POINT_HEADER_SIZE = 1; // data[0]：路径点数量
    private static final int PATH_POINT_SPEED_OFFSET = 4; // 点内相对字段[4]，完整数组为 data[5]
    private static int CoilRequest = 0;
    static short remoteCtrlNum = 0;//遥控计数

    @Autowired
    TcpClientService tcpClientService;

    public static void pathpointToPlc(JSONObject jsonMessage) {
        try {
            ModbusTcpClientService.acquirePriorityLock(ModbusTcpClientService.PATH_PRIORITY);
            // 初始化
            JSONArray pointsJson = jsonMessage.getJSONArray("points");
            if (pointsJson.length() < PATH_POINT_HEADER_SIZE + PATH_POINT_FIELD_COUNT
                    || (pointsJson.length() - PATH_POINT_HEADER_SIZE) % PATH_POINT_FIELD_COUNT != 0) {
                log.error("路径点数组长度不符合 path_point 协议: dataSize={}", pointsJson.length());
                return;
            }
            int pointCount = (pointsJson.length() - PATH_POINT_HEADER_SIZE) / PATH_POINT_FIELD_COUNT;
            int declaredPointCount = pointsJson.getInt(0);
            if (declaredPointCount != pointCount) {
                log.error("路径点头部数量与实际数量不一致: declared={}, actual={}",
                        declaredPointCount, pointCount);
                return;
            }
            int totalPoints = pointCount + 1;
            pointsendnum = 1;
            // 检查中断状态（如果已被中断，直接退出）
            if (Thread.currentThread().isInterrupted()) {
                log.warn("任务已被中断，终止执行");
                return;
            }
            // 发送起始点
            // data[0] 是数量；首个路径点的速度位于相对字段[4]，即完整数组 data[5]。
            if (!sendStartingPoint((float) pointsJson.getDouble(PATH_POINT_HEADER_SIZE + PATH_POINT_SPEED_OFFSET))) {
                log.error("初始位置发送失败，终止流程");
                return;
            }
            sendProgressUpdate(totalPoints, pointsendnum);
            pointsendnum++;
            CoilRequest = 0;
            // 主发送循环
            for (int i = PATH_POINT_HEADER_SIZE; i < pointsJson.length(); i += PATH_POINT_FIELD_COUNT) {
                // 每次循环前检查中断状态
                if (Thread.currentThread().isInterrupted()) {
                    log.warn("任务被中断，停止发送路径点");
                    break; // 退出循环
                }
                int retryCount = 0;
                boolean pointAccepted = false;

                while (!pointAccepted && retryCount < MAX_RETRIES) {
                    try {
                        if (CoilRequest == 0) {
                            // 重置确认线圈
                            if (!ModbusTcpClientService.writeSingleCoil(confirmCoilAddress, false)) {
                                log.warn("重置确认线圈失败");
                                retryCount++;
                                continue;
                            }
                            CoilRequest = 1;
                        }

                        // 准备并发送路径点
                        PointInfo point = preparePointInfo(pointsJson, i);
                        int[] registers = convertPointToRegisters(point);
                        if (!ModbusTcpClientService.writeMultipleRegisters(dataStartAddress, registers)) {
                            log.warn("写入路径点数据失败");
                            retryCount++;
                            continue;
                        }
                        // 检查确认状态
                        pointAccepted = checkConfirmation(confirmCoilAddress);
                        if (pointAccepted) {
                            log.info("成功发送路径点 {}/{}", pointsendnum, totalPoints);
                        } else {
                            retryCount++;
                            log.warn("路径点确认超时，重试 {}/{}", retryCount, MAX_RETRIES);
                        }
                    } catch (InterruptedException e) {
                        // 如果 sleep 被中断，直接退出
                        log.warn("任务被中断，停止重试");
                        Thread.currentThread().interrupt(); // 恢复中断状态
                        return;
                    } catch (Exception e) {
                        log.error("发送路径点时发生异常", e);
                        retryCount++;
                        try {
                            Thread.sleep(RETRY_INTERVAL_MS);
                        } catch (InterruptedException ie) {
                            log.warn("休眠期间被中断，终止任务");
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                }
                if (!pointAccepted) {
                    log.error("路径点发送最终失败，重新尝试");
                    i -= PATH_POINT_FIELD_COUNT;
                    continue;
                }
                // 发送进度更新
                sendProgressUpdate(totalPoints, pointsendnum);
                pointsendnum++;
                CoilRequest = 0;
            }
        } catch (Exception e) {
            log.error("路径点发送过程中发生异常", e);
        } finally {
            ModbusTcpClientService.releasePriorityLock();
        }
    }

    /**
     * 检查PLC确认状态
     */
    private static boolean checkConfirmation(int coilAddress) throws InterruptedException {
        for (int i = 0; i < MAX_COIL_CHECKS; i++) {
            boolean[] coils = ModbusTcpClientService.readCoils(coilAddress, 1);
            if (coils.length > 0 && coils[0]) {
                return true;
            }
            Thread.sleep(COIL_CHECK_INTERVAL);
        }
        return false;
    }

    /**
     * 将PointInfo转换为寄存器数组
     */
    private static int[] convertPointToRegisters(PointInfo point) {
        // 转换为int数组(每2字节一个寄存器)
        byte[] bytes = serializePointInfoToRegisters(point);
        int[] registers = new int[bytes.length / 2];
        for (int i = 0; i < registers.length; i++) {
            registers[i] = ((bytes[2*i] & 0xFF) << 8) | (bytes[2*i+1] & 0xFF);
        }

        return registers;
    }
    /**
     * 准备路径点数据
     */
    public static PointInfo preparePointInfo(JSONArray pointsJson, int index) throws JSONException {
        PointInfo point = new PointInfo();
        point.id = (byte) pointsendnum;
        point.lon = validateCoordinate(pointsJson.getDouble(index), "经度");
        point.lat = validateCoordinate(pointsJson.getDouble(index + 1), "纬度");
        // 点内相对字段：[4]速度，[5]运行模式，[6]定位模式；PLC 将后两者编码到 action。
        point.action = (byte) ((pointsJson.getInt(index + 6)) | ((pointsJson.getInt(index + 5)) << 3));
        point.speed = (float) validateSpeed(pointsJson.getDouble(index + 4));
        return point;
    }
    /**
     * 将PointInfo对象序列化为Modbus寄存器格式(每寄存器2字节)
     */
    private static byte[] serializePointInfoToRegisters(PointInfo point) {
        ByteBuffer buffer = ByteBuffer.allocate(24); // 示例: 10个寄存器(20字节)
        buffer.putShort(point.id);
        buffer.putDouble(point.lon);
        buffer.putDouble(point.lat);
        buffer.putShort(point.action);
        buffer.putFloat(point.speed);

        return buffer.array();
    }
    /**
     * 验证坐标值是否在合理范围内
     */
    private static double validateCoordinate(double value, String name) {
        if (name.equals("经度") && (value < -180 || value > 180)) {
            throw new IllegalArgumentException("无效的经度值: " + value);
        }
        if (name.equals("纬度") && (value < -90 || value > 90)) {
            throw new IllegalArgumentException("无效的纬度值: " + value);
        }
        return value;
    }
    /**
     * 验证速度值是否在合理范围内
     */
    private static double validateSpeed(double speed) {
        if (speed < 0 || speed > 10) { // 假设最大速度为10
            throw new IllegalArgumentException("无效的速度值: " + speed);
        }
        return speed;
    }
    /**
     * 发送进度更新到WebSocket
     */
    private static void sendProgressUpdate(int totalPoints, int sentPoints) throws JSONException {
        JSONObject progressInfo = new JSONObject();
        progressInfo.put("action", "PATHPOINT");
        progressInfo.put("pointnum", totalPoints)
                .put("pointsendnum", sentPoints);
        WebSocketServer.sendInfo(progressInfo.toString(), null);
    }
    /**
     * 发送当前位置到PLC
     * @param speed 速度值
     * @return 是否发送成功
     */
    public static Boolean sendStartingPoint(float speed) throws InterruptedException {
        int retryCount = 0;
        boolean pointAccepted = false;
        while (!pointAccepted && retryCount < MAX_RETRIES) {
            try {
                if (CoilRequest == 0){
                    //重置确认线圈
                    if (!ModbusTcpClientService.writeSingleCoil(confirmCoilAddress, false)) {
                        log.warn("重置确认线圈失败");
                        retryCount++;
                        continue;
                    }
                    CoilRequest = 1;
                }
                //准备并发送路径点
                // 创建当前位置点数据
                PointInfo point = new PointInfo();
                point.id = (byte) pointsendnum;
                point.lon = RevdataController.Lon;
                point.lat = RevdataController.Lat;
                point.action = (byte) 0;
                point.speed = speed;
                int[] registers = convertPointToRegisters(point);
                if (!ModbusTcpClientService.writeMultipleRegisters(dataStartAddress, registers)) {
                    log.warn("写入路径点数据失败");
                    retryCount++;
                    continue;
                }
                //检查确认状态
                pointAccepted = checkConfirmation(confirmCoilAddress);
                if (pointAccepted) {
                    log.info("PLC确认接收当前位置");
                    return true;
                } else {
                    retryCount++;
                    log.warn("路径点确认超时，重试 {}/{}", retryCount, MAX_RETRIES);
                }
            } catch (Exception e) {
                log.error("发送路径点时发生异常", e);
                retryCount++;
                Thread.sleep(RETRY_INTERVAL_MS);
            }
        }
        return false;
    }


    public static void remotectrlInfoToPlc(JSONObject jsonMessage) throws JSONException, IOException {
        //定义变量
        JSONObject remoteJson = jsonMessage.getJSONObject("remote");
        ByteBuffer buffer = ByteBuffer.allocate(18);
        remoteCtrlNum++;
        buffer.putShort(remoteCtrlNum);
        buffer.putFloat((float) remoteJson.getDouble("CTR_X1")).array();
        buffer.putFloat((float) remoteJson.getDouble("CTR_Y1")).array();
        buffer.putFloat((float) remoteJson.getDouble("CTR_X2")).array();
        buffer.putFloat((float) remoteJson.getDouble("CTR_Y2")).array();

        int[] registers = new int[buffer.array().length / 2];
        for (int i = 0; i < registers.length; i++) {
            registers[i] = ((buffer.array()[2*i] & 0xFF) << 8) | (buffer.array()[2*i+1] & 0xFF);
        }
        try {
            ModbusTcpClientService.acquirePriorityLock(ModbusTcpClientService.OTHER_PRIORITY);
            ModbusTcpClientService.writeMultipleRegisters(30, registers);
        } finally {
            ModbusTcpClientService.releasePriorityLock();
        }

    }

    public static void paramToPlc(JSONObject jsonMessage) throws JSONException, IOException, InterruptedException {
        //定义变量
        JSONObject paramJson = jsonMessage.getJSONObject("param");
        ByteBuffer buffer = ByteBuffer.allocate(48);
        buffer.putDouble(paramJson.getDouble("Lon")).array();
        buffer.putDouble(paramJson.getDouble("Lat")).array();
        buffer.putFloat((float)paramJson.getDouble("forwordDis")).array();
        buffer.putFloat((float)paramJson.getDouble("wheelBase")).array();
        buffer.putFloat((float)paramJson.getDouble("speed_max")).array();
        buffer.putFloat((float)paramJson.getDouble("relative_x")).array();
        buffer.putFloat((float)paramJson.getDouble("relative_y")).array();
        buffer.putFloat((float)paramJson.getDouble("rotation")).array();
        buffer.putFloat((float)paramJson.getDouble("stop_set")).array();
        buffer.putFloat((float)paramJson.getDouble("speed_down")).array();
        int[] registers = new int[buffer.array().length / 2];
        for (int i = 0; i < registers.length; i++) {
            registers[i] = ((buffer.array()[2*i] & 0xFF) << 8) | (buffer.array()[2*i+1] & 0xFF);
        }
        ModbusTcpClientService.writeMultipleRegisters(39, registers);
        ModbusTcpClientService.writeSingleCoil(4, true);
        //参数设置线圈置True
        byte[] request = TcpClientService.createWriteSingleCoilRequest(4, true);
        TcpClientService.sendModbusRequest(request);
        //查看线圈状态，False--参数写入完成
        boolean[] coils = ModbusTcpClientService.readCoils(4, 1);
        if (coils.length > 0 && coils[4]) {
            TcpClientService.sendProgressUpdate("参数设置","成功","1");
        }else if(coils.length > 0 && !coils[4]){
            TcpClientService.sendProgressUpdate("参数设置","失败","0");
        }else{
            TcpClientService.sendProgressUpdate("参数设置","通信错误","0");
        }
    }

}
