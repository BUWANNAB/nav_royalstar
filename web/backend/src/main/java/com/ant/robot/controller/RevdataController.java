package com.ant.robot.controller;

import com.ant.robot.mapper.ParamMapper;
import com.ant.robot.model.domain.Llh2xyz;
import com.ant.robot.model.domain.Param;
import com.ant.robot.service.TcpClientService;
import com.ant.robot.websocket.WebSocketServer;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import jakarta.annotation.Resource;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONException;
import org.json.JSONObject;
import org.springframework.stereotype.Service;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

@Slf4j
@Data
@Service
public class RevdataController {

    private double High;
    public static double Lat;
    public static double Lon;
    public static double Local_High;
    public static double Local_Lat;
    public static double Local_Lon;
    public static double Head;
    public static double LocalX;
    public static double LocalY;
    public static double ActVoltage;
    public static double[] data;
    public static String LOCSTATE;
    public static String HEADSTATE;
    public static byte runStatus;
    public static byte routeEnd;
    public static boolean statusOvI;      // 电流状态
    public static boolean statusOvU;      // 电压状态
    public static boolean statusErrEnc;   // 编码器状态
    public static boolean statusOvT;      // 位置偏差状态
    public static boolean statusOvQ;      // 欠压状态
    public static boolean statusOvLoad;   // 过载状态
    public static byte canError;           // CAN通信错误

    @Resource
    private ParamMapper paramMapper;
    @Resource
    private Llh2xyzController llh2xyzController;

    // 解析并处理数据
    public void parseAndProcessData(byte[] buffer) throws JSONException {
        log.info("buffer {}",buffer);
        if (buffer == null || buffer.length < 60) {
            // 检查缓冲区是否有足够的数据
            return;
        }

        QueryWrapper<Param> paramQueryWrapper = new QueryWrapper<>();
        Param param = paramMapper.selectOne(paramQueryWrapper);
        Local_Lat = Double.parseDouble(param.getLocal_origin_latitude());
        Local_Lon = Double.parseDouble(param.getLocal_origin_longitude());

        ByteBuffer byteBuffer = ByteBuffer.wrap(buffer);
        byteBuffer.order(ByteOrder.BIG_ENDIAN); // 数据为大端字节序
        Llh2xyz llh2xyz = new Llh2xyz();
        // 解析各字段
        Head = byteBuffer.getDouble(0); // 第 0-7 字节
        llh2xyz.z = byteBuffer.getDouble(8); // 第 8-15 字节
        llh2xyz.x = (byteBuffer.getDouble(16)); // 第 16-23 字节
        llh2xyz.y = (byteBuffer.getDouble(24)); // 第 24-31 字节
        runStatus = byteBuffer.get(33);
        ActVoltage = (byteBuffer.getFloat(34));
        routeEnd = byteBuffer.get(63);

        // 解析驱动器状态 (从原PLC代码的arAi_data[32]-[38])
        canError = byteBuffer.get(64);        // 对应arAi_data[32]
        statusOvI = byteBuffer.get(65) != 0;  // 对应arAi_data[33]
        statusOvU = byteBuffer.get(66) != 0;  // 对应arAi_data[34]
        statusErrEnc = byteBuffer.get(67) != 0; // 对应arAi_data[35]
        statusOvT = byteBuffer.get(68) != 0;  // 对应arAi_data[36]
        statusOvQ = byteBuffer.get(69) != 0;  // 对应arAi_data[37]
        statusOvLoad = byteBuffer.get(70) != 0; // 对应arAi_data[38]

        // CAN通信错误处理
        if(!statusOvI || canError == 1) {
            TcpClientService.sendProgressUpdate("can通信", "超时", "0");
        }

        if(byteBuffer.get(65) == 1){
            TcpClientService.sendProgressUpdate("can通信","超时","0");
        }

        double[] latLon = llh2xyzController.convertFromLocalCoordinates(Local_Lat, Local_Lon, llh2xyz.x, llh2xyz.y,llh2xyz.z);

        Lat = latLon[0];
        Lon = latLon[1];
        LocalX = llh2xyz.x;
        LocalY = llh2xyz.y;

        int stringStartIndex = 38;
        byte[] stringBytes = Arrays.copyOfRange(buffer, stringStartIndex, 48);
        LOCSTATE = new String(stringBytes, StandardCharsets.UTF_8); // 转为字符串
        String locstate;
        if ("NARROW_INT".equals(LOCSTATE)) {
            LOCSTATE = "正常";
            locstate = "Normal";
        } else {
            LOCSTATE = "错误";
            locstate = "Error";
        }
        stringStartIndex = 50;
        stringBytes = Arrays.copyOfRange(buffer, stringStartIndex, 60);
        HEADSTATE = new String(stringBytes, StandardCharsets.UTF_8); // 转为字符串
        log.info("HEADSTATE {}",HEADSTATE);
        if ("NARROW_INT".equals(HEADSTATE)) {
            HEADSTATE = "Normal";
        } else {
            HEADSTATE = "Error";
        }
        log.info("Latlon[{}, {}], pose: [ x:{}, y:{}, z: {}], heading: {}, LOCSTATE: {}, HEADSTATE: {}",
                Lat, Lon, llh2xyz.x,llh2xyz.y,llh2xyz.z, Head, locstate, HEADSTATE);
        broadcastPose();
    }

    //发送到websocket服务器上所有的连接客户端
    private void broadcastPose() throws JSONException {
        JSONObject poseData = new JSONObject();
        poseData.put("action", "carCurrentPosition");
        poseData.put("position", new JSONObject()
                .put("Lat", Lat)
                .put("Lon", Lon)
                .put("High", High)
                .put("Heading",Head)
                .put("LocalX",LocalX)
                .put("LocalY",LocalY)
                .put("STANT",LOCSTATE)
                .put("HEAD",HEADSTATE)
                .put("runStatus",runStatus)
                .put("routeEnd",routeEnd));

        WebSocketServer.sendInfo(poseData.toString(),null);
    }
}
