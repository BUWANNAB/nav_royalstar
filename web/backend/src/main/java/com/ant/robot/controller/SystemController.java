package com.ant.robot.controller;

import com.ant.robot.common.aop.LogAnnotation;
import com.ant.robot.common.constants.SystemConstant;
import com.ant.robot.common.response.BaseResponse;
import com.ant.robot.common.response.ResultUtils;
import com.ant.robot.model.vo.FileVo;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.DecimalFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * @author ChenWeihan
 * @description 系统控制类，下载文件
 */
@RestController
@RequestMapping("system")
@Slf4j
public class SystemController {

    /**
     * 下载指定目录下文件
     */
    private static String FILE_DIRECTORY = "D:\\桌面\\蓝蚁";

    /**
     * 定义1MB的字节数
     */
    private static final long MB_IN_BYTES = 1024L * 1024L;

    /**
     * 用于格式化数字到两位小数
     */
    private static final DecimalFormat DF = new DecimalFormat("#.##");


    /**
     * 通过扩展名判断文件类型
     *
     * @return
     */
    private String getFileTypeByEnd(String fileName) {
        String fileType = "";
        if (fileName.lastIndexOf(".") > 0) {
            fileType = fileName.substring(fileName.lastIndexOf(".") + 1).toLowerCase();
        }

        String result = "";

        switch (fileType) {
            case SystemConstant.FILE_TYPE_CSV:
                result = "CSV文件";
                break;
            case SystemConstant.FILE_TYPE_BAG:
                result = "BAG文件";
                break;
            case SystemConstant.FILE_TYPE_ZIP:
                result = "ZIP文件";
                break;
            case SystemConstant.FILE_TYPE_TXT:
                result = "TXT文件";
                break;
            default:
                result = "未知文件";
        }

        return result;
    }


    /**
     * 获取文件列表
     */
    @GetMapping("list")
    public BaseResponse<List<FileVo>> list() {
        if (StringUtils.isEmpty(FILE_DIRECTORY)) {
            FILE_DIRECTORY = "/home/" + System.getProperty("user.name") + "/AppData";
        }
        List<FileVo> fileVos = null;
        try {
            AtomicLong fileNo = new AtomicLong(1);
            fileVos = Files.list(Paths.get(FILE_DIRECTORY))
                    .filter(Files::isRegularFile)
                    .map(path -> {
                        FileVo fileVo = new FileVo();
                        fileVo.setFileNo(fileNo.getAndIncrement());
                        fileVo.setFileName(path.getFileName().toString());
                        fileVo.setFileType(getFileTypeByEnd(fileVo.getFileName()));
                        long fileSizeInBytes;
                        try {
                            fileSizeInBytes = Files.size(path);
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                        double fileSizeInMB = (double) fileSizeInBytes / MB_IN_BYTES;
                        // 使用DecimalFormat进行四舍五入并格式化到两位小数
                        String formattedSize = DF.format(fileSizeInMB);
                        fileVo.setFileSize(Double.parseDouble(formattedSize)); // 注意这里转换回double类型
                        return fileVo;
                    })
                    .collect(Collectors.toList());
        } catch (IOException e) {
            e.printStackTrace();
        }
        return ResultUtils.success(fileVos);
    }

    /**
     * 下载文件
     */
    @LogAnnotation(title = "系统模块", content = "下载文件")
    @GetMapping("download/{fileName}")
    public ResponseEntity<StreamingResponseBody> downloadRosBag(@PathVariable String fileName) {
        Path filePath = Paths.get(FILE_DIRECTORY).resolve(fileName + ".zip");

        if (!Files.exists(filePath)) {
            return ResponseEntity.notFound().build();
        }

        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filePath.getFileName() + "\"");
        headers.add(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_OCTET_STREAM_VALUE);

        try {
            return ResponseEntity.ok()
                    .headers(headers)
                    .body(out -> Files.copy(filePath, out));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
}
