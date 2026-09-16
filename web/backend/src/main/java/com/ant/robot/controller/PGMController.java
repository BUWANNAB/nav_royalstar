package com.ant.robot.controller;

import com.ant.robot.common.enums.ErrorCode;
import com.ant.robot.common.response.BaseResponse;
import com.ant.robot.common.response.ResultUtils;
import com.ant.robot.model.vo.PgmMapNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.MalformedURLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@RestController
@RequestMapping("pgm")
@Slf4j
public class PGMController {

    @Value("${pgm.root.path}")
    private String pgmRootPath;

    private static final String SETTING_DIR = "setting";
    private static final String PGM_FILE_NAME = "map.pgm";
    private static final String YAML_FILE_NAME = "map.yaml";

    /**
     * 获取地图列表
     *
     * @return 包含地图节点列表的统一响应结果
     */
    @GetMapping("/list")
    public BaseResponse<List<PgmMapNode>> getMapList() {
        // 验证并初始化PGM根目录
        Path root;
        try {
            root = Paths.get(pgmRootPath).toAbsolutePath().normalize();
            if (!Files.exists(root)) {
                Files.createDirectories(root);
            }
        } catch (IOException e) {
            log.error("PGM 根目录无效或创建失败: {}", pgmRootPath, e);
            return ResultUtils.error(ErrorCode.SYSTEM_ERROR, "PGM 根目录无效");
        }

        // 递归查找所有地图文件并构建树形结构
        List<PgmMapNode> tree = findMapsRecursively(root, root);
        return ResultUtils.success(tree);
    }


    /**
     * 递归查找PGM地图文件并构建节点树结构
     *
     * @param currentDir 当前扫描的目录路径
     * @param rootDir 根目录路径，用于计算相对路径
     * @return 包含PGM地图节点的列表
     */
    private List<PgmMapNode> findMapsRecursively(Path currentDir, Path rootDir) {
        List<PgmMapNode> nodes = new ArrayList<>();

        // 获取当前目录下的所有子目录，并过滤掉设置目录
        try (Stream<Path> stream = Files.list(currentDir)) {
            List<Path> directories = stream
                    .filter(Files::isDirectory)
                    .filter(dir -> !dir.getFileName().toString().equals(SETTING_DIR))
                    .collect(Collectors.toList());

            // 遍历每个子目录，检查是否存在PGM和YAML配置文件
            for (Path dir : directories) {
                Path pgmFile = dir.resolve(SETTING_DIR).resolve(PGM_FILE_NAME);
                Path yamlFile = dir.resolve(SETTING_DIR).resolve(YAML_FILE_NAME);

                // 如果同时存在PGM文件和YAML文件，则创建节点并递归处理子目录
                if (Files.exists(pgmFile) && Files.exists(yamlFile)) {
                    String dirName = dir.getFileName().toString();
                    String relativePath = rootDir.relativize(dir).toString().replace("\\", "/");
                    PgmMapNode node = new PgmMapNode(dirName, relativePath);
                    node.getChildren().addAll(findMapsRecursively(dir, rootDir));
                    nodes.add(node);
                }
            }
        } catch (IOException e) {
            log.error("扫描PGM目录时出错: {}", currentDir, e);
        }
        return nodes;
    }



    /**
     * 获取PGM文件接口
     * 通过指定的地图名称获取对应的PGM文件数据
     *
     * @param mapName 地图名称，作为路径变量传入
     * @return ResponseEntity<?> 包含文件数据的响应实体，文件类型为APPLICATION_OCTET_STREAM
     */
    @GetMapping("/file/pgm/{mapName}")
    public ResponseEntity<?> getPgmFile(@PathVariable String mapName) {
        // 调用通用文件获取方法，获取PGM格式文件
        return getFile(mapName, PGM_FILE_NAME, MediaType.APPLICATION_OCTET_STREAM);
    }


    /**
     * 获取指定地图名称的YAML配置文件
     *
     * @param mapName 地图名称，用于标识要获取的YAML文件
     * @return ResponseEntity<?> 包含文件内容的响应实体，文件类型为文本格式
     */
    @GetMapping("/file/yaml/{mapName}")
    public ResponseEntity<?> getYamlFile(@PathVariable String mapName) {
        return getFile(mapName, YAML_FILE_NAME, MediaType.TEXT_PLAIN);
    }


    /**
     * 获取文件的通用辅助方法
     *
     * @param mapName          地图名称，用于定位地图目录（需通过白名单校验）
     * @param specificFileName 目标文件名，将在指定地图的 setting 子目录中查找该文件
     * @param mediaType        返回资源的媒体类型，例如 text/xml 或 application/json
     * @return ResponseEntity 包含文件资源或错误信息的结果响应体
     */
    private ResponseEntity<?> getFile(String mapName, String specificFileName, MediaType mediaType) {
        // 使用更严格的 isValidNameParameter 进行白名单校验
        if (!isValidNameParameter(mapName)) {
            log.warn("无效的地图名称请求: {}", mapName);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ResultUtils.error(ErrorCode.PARAMS_ERROR, "地图名称无效"));
        }

        try {
            Path root = Paths.get(pgmRootPath).toAbsolutePath().normalize();

            // 1. 根据地图名称查找对应的地图目录（不涉及路径拼接，避免目录遍历风险）
            Path mapDir = findMapDirectoryByName(root, mapName);

            // 2. 检查是否成功找到地图目录
            if (mapDir == null) {
                log.warn("未能在任何子目录中找到地图: {}", mapName);
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(ResultUtils.error(ErrorCode.SYSTEM_ERROR, "未找到地图: " + mapName));
            }

            // 构造目标文件路径：地图目录下的 setting 子目录中的具体文件
            Path filePath = mapDir.resolve(SETTING_DIR).resolve(specificFileName);

            Resource resource = new UrlResource(filePath.toUri());

            // 判断资源是否存在且可读，并构造成功的响应实体
            if (resource.exists() && resource.isReadable()) {
                return ResponseEntity.ok()
                        .contentType(mediaType)
                        .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + resource.getFilename() + "\"")
                        .body(resource);
            } else {
                log.warn("地图目录已找到, 但文件不存在: {}", filePath);
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(ResultUtils.error(ErrorCode.SYSTEM_ERROR, "文件未找到"));
            }
        } catch (MalformedURLException e) {
            log.error("创建文件URL时出错: {}", mapName, e);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ResultUtils.error(ErrorCode.PARAMS_ERROR, "路径格式错误"));
        } catch (Exception e) {
            log.error("获取PGM/YAML文件时发生未知错误", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ResultUtils.error(ErrorCode.SYSTEM_ERROR, e.getMessage()));
        }
    }



    /**
     * 根据地图名称在目录中查找对应的地图文件夹
     *
     * @param currentDir 当前搜索的目录路径
     * @param mapName 要查找的地图名称
     * @return 找到的地图目录路径，如果未找到则返回null
     */
    private Path findMapDirectoryByName(Path currentDir, String mapName) {
        // 检查当前目录是否匹配地图名称，并且包含必要的配置文件
        if (currentDir.getFileName().toString().equals(mapName)) {
            Path pgmFile = currentDir.resolve(SETTING_DIR).resolve(PGM_FILE_NAME);
            Path yamlFile = currentDir.resolve(SETTING_DIR).resolve(YAML_FILE_NAME);
            if (Files.exists(pgmFile) && Files.exists(yamlFile)) {
                return currentDir;
            }
        }

        // 如果当前路径是目录，则递归搜索其子目录
        if (Files.isDirectory(currentDir)) {
            try (Stream<Path> stream = Files.list(currentDir)) {
                // 过滤出子目录，排除设置目录
                List<Path> subDirs = stream
                        .filter(Files::isDirectory)
                        .filter(dir -> !dir.getFileName().toString().equals(SETTING_DIR))
                        .collect(Collectors.toList());
                // 递归搜索每个子目录
                for (Path subDir : subDirs) {
                    Path found = findMapDirectoryByName(subDir, mapName);
                    if (found != null) {
                        return found;
                    }
                }
            } catch (IOException e) {
                log.error("搜索地图时发生IO异常: {}", currentDir, e);
                return null;
            }
        }
        return null;
    }

    /**
     * 保存地图文件（PGM 和 YAML）到指定路径。
     * <p>
     * 该方法接收客户端上传的 PGM 文件和 YAML 文件，并将其保存至服务器指定目录下。
     * 路径参数会经过安全校验以防止路径遍历攻击。若目标目录不存在或文件为空，则返回错误信息。
     * </p>
     *
     * @param path      相对于根目录的地图存储路径，必须是一个合法、安全的子路径
     * @param pgmFile   上传的 PGM 地图图像文件，不能为空
     * @param yamlFile  上传的 YAML 配置文件，不能为空
     * @return          操作结果封装在 BaseResponse 中，成功则返回“保存成功”，失败则返回具体错误信息
     */
    @PostMapping("/save")
    public BaseResponse<?> saveMap(
            @RequestParam("path") String path,
            @RequestParam("pgmFile") MultipartFile pgmFile,
            @RequestParam("yamlFile") MultipartFile yamlFile) {

        // 校验路径是否合法，防止路径注入攻击
        if (!isValidPathParameter(path)) {
            log.warn("保存地图时路径无效: {}", path);
            return ResultUtils.error(ErrorCode.PARAMS_ERROR, "路径无效");
        }

        // 判断上传文件是否为空
        if (pgmFile.isEmpty() || yamlFile.isEmpty()) {
            return ResultUtils.error(ErrorCode.PARAMS_ERROR, "上传文件不能为空");
        }

        try {
            Path root = Paths.get(pgmRootPath).toAbsolutePath().normalize();

            // 使用安全解析方法构造目标地图目录路径
            Path mapDir = resolveSecurePath(root, path);

            Path settingDir = mapDir.resolve(SETTING_DIR);
            // 确保设置目录存在且为目录类型
            if (!Files.exists(settingDir) || !Files.isDirectory(settingDir)) {
                log.warn("保存时地图路径不存在: {}", path);
                return ResultUtils.error(ErrorCode.SYSTEM_ERROR, "地图路径不存在: " + path);
            }

            Path pgmPath = settingDir.resolve(PGM_FILE_NAME);
            Path yamlPath = settingDir.resolve(YAML_FILE_NAME);

            // 限制上传文件大小不超过 10MB
            if (pgmFile.getSize() > 10 * 1024 * 1024 || yamlFile.getSize() > 10 * 1024 * 1024) {
                return ResultUtils.error(ErrorCode.PARAMS_ERROR, "文件过大");
            }

            // 将上传文件写入磁盘，如已存在则覆盖
            Files.copy(pgmFile.getInputStream(), pgmPath, StandardCopyOption.REPLACE_EXISTING);
            Files.copy(yamlFile.getInputStream(), yamlPath, StandardCopyOption.REPLACE_EXISTING);

            log.info("地图已保存 (替换): {}", path);
            return ResultUtils.success("保存成功");

        } catch (SecurityException | IllegalArgumentException e) {
            log.warn("保存时检测到路径遍历或非法参数: {}", path, e);
            return ResultUtils.error(ErrorCode.PARAMS_ERROR, "路径无效: " + e.getMessage());
        } catch (IOException e) {
            log.error("保存地图时发生IO异常: {}", path, e);
            return ResultUtils.error(ErrorCode.SYSTEM_ERROR, "保存失败: " + e.getMessage());
        }
    }

    /**
     * 将上传的地图文件（PGM 和 YAML）另存为一个新的地图。
     *
     * @param parentPath 父级目录路径，可选。如果未提供，则保存到根目录下。
     * @param newName 新地图的名称，必须唯一且符合命名规范。
     * @param pgmFile PGM格式的地图图像文件。
     * @param yamlFile YAML格式的地图配置文件。
     * @return 操作结果响应对象，成功时返回新创建的地图节点信息，失败时返回错误提示。
     */
    @PostMapping("/save-as")
    public BaseResponse<?> saveMapAs(
            @RequestParam(value = "parentPath", required = false) String parentPath,
            @RequestParam("newName") String newName,
            @RequestParam("pgmFile") MultipartFile pgmFile,
            @RequestParam("yamlFile") MultipartFile yamlFile) {

        // 校验新地图名称是否合法
        if (!isValidNameParameter(newName)) {
            log.warn("另存为地图时名称无效: {}", newName);
            return ResultUtils.error(ErrorCode.PARAMS_ERROR, "新地图名称无效");
        }

        // 校验上传文件是否为空
        if (pgmFile.isEmpty() || yamlFile.isEmpty()) {
            return ResultUtils.error(ErrorCode.PARAMS_ERROR, "上传文件不能为空");
        }

        try {
            Path root = Paths.get(pgmRootPath).toAbsolutePath().normalize();
            Path parentDir;

            // 在 resolveSecurePath 之前，增加对 parentPath 的显式校验
            if (parentPath != null && !parentPath.isEmpty() && !isValidPathParameter(parentPath)) {
                log.warn("另存为地图时父路径参数非法: {}", parentPath);
                return ResultUtils.error(ErrorCode.PARAMS_ERROR, "父路径参数非法");
            }

            // 解析并验证父级路径
            if (parentPath == null || parentPath.isEmpty()) {
                parentDir = root; // 默认存储在根目录
            } else {
                // 使用安全解析方法防止路径遍历攻击
                parentDir = resolveSecurePath(root, parentPath);
                if (!Files.isDirectory(parentDir)) {
                    log.warn("另存为地图时父路径无效: {}", parentPath);
                    return ResultUtils.error(ErrorCode.PARAMS_ERROR, "父路径无效");
                }
            }

            // 构造新的地图目录路径，并检查是否存在同名地图
            Path newMapDir = parentDir.resolve(newName);

            if (Files.exists(newMapDir)) {
                return ResultUtils.error(ErrorCode.PARAMS_ERROR, "地图名称已存在: " + newName);
            }

            // 创建地图设置子目录
            Path newSettingDir = newMapDir.resolve(SETTING_DIR);
            Files.createDirectories(newSettingDir);

            // 定义目标文件路径
            Path pgmPath = newSettingDir.resolve(PGM_FILE_NAME);
            Path yamlPath = newSettingDir.resolve(YAML_FILE_NAME);

            // 保存上传的文件
            pgmFile.transferTo(pgmPath);
            yamlFile.transferTo(yamlPath);

            log.info("地图已另存为: {}", newMapDir.toString());

            // 计算相对路径用于返回给前端
            String newRelativePath = root.relativize(newMapDir).toString().replace("\\", "/");
            PgmMapNode newNode = new PgmMapNode(newName, newRelativePath);
            return ResultUtils.success(newNode);

        } catch (SecurityException | IllegalArgumentException e) {
            log.warn("另存为地图时检测到路径遍历或非法参数: parentPath={}, newName={}", parentPath, newName, e);
            return ResultUtils.error(ErrorCode.PARAMS_ERROR, "路径无效: " + e.getMessage());
        } catch (IOException e) {
            log.error("另存为地图时发生IO异常", e);
            return ResultUtils.error(ErrorCode.SYSTEM_ERROR, "另存为失败: " + e.getMessage());
        }
    }



    /**
     * 校验路径参数是否合法 (防止 ../ 和绝对路径)
     *
     * @param param 待校验的路径参数字符串
     * @return 如果路径参数合法返回true，否则返回false
     */
    private boolean isValidPathParameter(String param) {
        if (param == null || param.isEmpty()) {
            return false;
        }

        // 使用正则表达式实现更严格的白名单：
        // 只允许字母、数字、下划线、连字符和正斜杠(作为路径分隔符)
        // 注意：我们仍然需要在 resolveSecurePath 中检查 ".."
        String regex = "^[a-zA-Z0-9_\\-/]+$";
        if (!param.matches(regex)) {
            return false;
        }

        // 保留对相对路径格式的检查（不允许绝对路径或悬空路径）。
        return !param.startsWith("/") &&
                !param.startsWith("\\") &&
                !param.endsWith("/") &&
                !param.endsWith("\\");
    }


    /**
     * 校验文件名参数是否合法 (白名单)
     *
     * @param name 待校验的文件名参数
     * @return 如果文件名合法返回true，否则返回false
     */
    private boolean isValidNameParameter(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }

        // 严格的白名单：只允许字母、数字、下划线和连字符
        String regex = "^[a-zA-Z0-9_\\-]+$";

        // 确保它不等于保留名称
        return name.matches(regex) && !name.equals(SETTING_DIR);
    }


    /**
     * 安全地解析用户提供的相对路径，并防止路径遍历
     *
     * @param root 绝对的、规范化的根目录
     * @param userPath 用户提供的相对路径
     * @return 一个在 root 内的、绝对的、规范化的路径
     * @throws IOException
     * @throws SecurityException 如果检测到路径遍历
     * @throws IllegalArgumentException 如果参数无效
     */
    private Path resolveSecurePath(Path root, String userPath) throws IOException {
        if (!isValidPathParameter(userPath)) {
            throw new IllegalArgumentException("Invalid path parameter format");
        }

        // 1. 规范化用户输入并解析
        Path resolvedPath = root.resolve(userPath).normalize();

        // 2. 核心安全校验：确保解析后的路径仍然在根目录之下
        if (!resolvedPath.startsWith(root)) {
            throw new SecurityException("Path traversal attempt detected");
        }

        return resolvedPath;
    }
}