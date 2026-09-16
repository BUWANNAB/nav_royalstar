package com.ant.robot.model.request;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 点击点坐标请求参数类
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "点击点坐标请求参数")
public class PointRequest {

    @Schema(description = "X坐标", required = true, example = "10.5")
    private Double x;

    @Schema(description = "Y坐标", required = true, example = "20.3")
    private Double z;

    @Schema(description = "Z坐标", required = false, example = "0.0")
    private Double y = 0.0;

    @Schema(description = "坐标系ID", required = false, example = "map")
    private String frameId = "map";

    @Schema(description = "时间戳", required = false, example = "1700000000")
    private Long timestamp;
}
