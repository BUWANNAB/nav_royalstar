package com.ant.robot.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.FileSystemUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@RestController
@RequestMapping("files")
//@CrossOrigin(origins = "*")
public class FileController {

    @Value("${file-service.base-path:/tmp}")
    private String basePath;
    // maps.directory 由 application.yml 注入（${ROBOT_STATIC_MAPS_DIR:...}，默认 <部署根>/runtime/static-maps）
    @Value("${maps.directory:./maps}")
    private String mapsDirectory;

    @Autowired  // 改为字段注入
    private ResourceLoader resourceLoader;


    // 获取所有文件和文件夹（非递归）
    @GetMapping("/list")
    public List<String> listFilesAndDirs(@RequestParam(required = false) String path) {
        String targetPath = path != null ? path : basePath;
        File directory = validatePath(targetPath);

        File[] files = directory.listFiles();
        if (files == null) {
            return new ArrayList<>();
        }

        return Arrays.stream(files)
                .map(file -> file.getName())
                .collect(Collectors.toList());
    }

    // 递归获取所有文件和文件夹
    @GetMapping("/list-all")
    public List<String> listAllFilesAndDirs(@RequestParam(required = false) String path) {
        String targetPath = path != null ? path : basePath;
        File directory = validatePath(targetPath);

        List<String> fileList = new ArrayList<>();
        listFilesAndDirsRecursive(directory, "", fileList);
        return fileList;
    }

    // 按扩展名过滤（仅文件）
    @GetMapping("/by-ext")
    public List<String> listFilesByExtension(
            @RequestParam(required = false) String path,
            @RequestParam String ext) {
        String targetPath = path != null ? path : basePath;
        File directory = validatePath(targetPath);

        return Arrays.stream(directory.listFiles())
                .filter(File::isFile)
                .filter(file -> file.getName().endsWith(ext))
                .map(File::getName)
                .collect(Collectors.toList());
    }

    // 删除文件或空目录
    @DeleteMapping("/Delete")
    public ResponseEntity<String> deleteFileOrDirectory(
            @RequestParam(required = false) String path,
            @RequestParam String name) throws IOException {
        String targetPath = path != null ? path : basePath;
        File directory = validatePath(targetPath);

        // 构造要删除的文件/目录对象
        File toDelete = new File(directory, name);

        // 再次安全检查
        if (!toDelete.getCanonicalPath().startsWith(new File(basePath).getCanonicalPath())) {
            throw new IllegalArgumentException("不允许删除基目录之外的文件: " + name);
        }

        if (!toDelete.exists()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body("文件或目录不存在: " + name);
        }

        if (toDelete.isDirectory()) {
            // 检查目录是否为空
            File[] files = toDelete.listFiles();
            if (files != null && files.length > 0) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body("目录不为空，无法删除: " + name);
            }

            if (toDelete.delete()) {
                return ResponseEntity.ok("目录删除成功: " + name);
            } else {
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body("目录删除失败: " + name);
            }
        } else {
            if (toDelete.delete()) {
                return ResponseEntity.ok("文件删除成功: " + name);
            } else {
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body("文件删除失败: " + name);
            }
        }
    }

    // 递归删除目录及其内容
    @DeleteMapping("/delete-recursive")
    public ResponseEntity<String> deleteDirectoryRecursive(
            @RequestParam(required = false) String path,
            @RequestParam String name) {
        String targetPath = path != null ? path : basePath;
        File directory = validatePath(targetPath);

        File toDelete = new File(directory, name);

        // 安全检查
        try {
            if (!toDelete.getCanonicalPath().startsWith(new File(basePath).getCanonicalPath())) {
                throw new IllegalArgumentException("不允许删除基目录之外的文件: " + name);
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("路径解析失败: " + name, e);
        }

        if (!toDelete.exists()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body("文件或目录不存在: " + name);
        }

        if (deleteRecursive(toDelete)) {
            return ResponseEntity.ok("删除成功: " + name);
        } else {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("删除失败: " + name);
        }
    }

    private boolean deleteRecursive(File file) {
        if (file.isDirectory()) {
            File[] files = file.listFiles();
            if (files != null) {
                for (File child : files) {
                    if (!deleteRecursive(child)) {
                        return false;
                    }
                }
            }
        }
        return file.delete();
    }

    private File validatePath(String path) {
        // 简单路径安全检查
        if (path.contains("../") || path.contains("..\\")) {
            throw new IllegalArgumentException("非法路径: " + path);
        }

        File directory = new File(path);

        // 确保路径在基目录下
        try {
            if (!directory.getCanonicalPath().startsWith(new File(basePath).getCanonicalPath())) {
                throw new IllegalArgumentException("非法路径: " + path);
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("路径解析失败: " + path, e);
        }

        if (!directory.exists() || !directory.isDirectory()) {
            throw new IllegalArgumentException("路径不存在或不是目录: " + path);
        }
        return directory;
    }

    private void listFilesAndDirsRecursive(File directory, String relativePath, List<String> fileList) {
        File[] files = directory.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isFile()) {
                    fileList.add(relativePath + file.getName());
                } else if (file.isDirectory()) {
                    fileList.add(relativePath + file.getName() + "/");
                    listFilesAndDirsRecursive(file, relativePath + file.getName() + "/", fileList);
                }
            }
        }
    }

    @PostMapping("/rename")
    public ResponseEntity<String> renameFile(
            @RequestParam String currentName,
            @RequestParam String newName) {
        System.out.println("hahaha");
        try {
            Path mapsPath = getMapsDirectoryPath();
            Path source = mapsPath.resolve(currentName);
            Path target = mapsPath.resolve(newName);

            // 验证路径是否合法（防止路径穿越）
            if (!source.normalize().startsWith(mapsPath.normalize())) {
                return ResponseEntity.badRequest().body("非法路径");
            }

            if (Files.exists(target)) {
                return ResponseEntity.badRequest().body("新文件名已存在");
            }
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
            return ResponseEntity.ok("重命名成功");
        } catch (IOException e) {
            // 添加详细错误日志
            System.err.println("重命名失败: " + e.getMessage());
            e.printStackTrace();
            return ResponseEntity.internalServerError().body("操作失败: " + e.getMessage());
        }
    }

    @DeleteMapping("/delete")
    public ResponseEntity<String> deleteFile(
            @RequestParam String fileName) {

        // 添加日志记录请求
        System.out.println("收到删除请求，文件名: " + fileName);

//        if (!isValidFileName(fileName)) {
//            System.err.println("文件名包含非法字符: " + fileName);
//            return ResponseEntity.badRequest().body("文件名包含非法字符: " + fileName);
//        }

        try {
            Path mapsPath = getMapsDirectoryPath();
            Path filePath = mapsPath.resolve(fileName);

            // 添加详细路径日志
            System.out.println("完整文件路径: " + filePath.toAbsolutePath());
            System.out.println("文件是否存在: " + Files.exists(filePath));
            System.out.println("是目录吗: " + Files.isDirectory(filePath));

            if (!Files.exists(filePath)) {
                // 返回详细错误信息
                String errorMsg = "文件不存在: " + filePath.toAbsolutePath();
                System.err.println(errorMsg);
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(errorMsg);
            }

            if (Files.isDirectory(filePath)) {
                System.out.println("正在删除目录: " + filePath);
                FileSystemUtils.deleteRecursively(filePath);
            } else {
                System.out.println("正在删除文件: " + filePath);
                Files.delete(filePath);
            }

            return ResponseEntity.ok("删除成功: " + fileName);
        } catch (IOException e) {
            String errorMsg = "删除失败: " + e.getMessage();
            System.err.println(errorMsg);
            e.printStackTrace();
            return ResponseEntity.internalServerError().body(errorMsg);
        }
    }




    /**
     * 获取Maps目录的绝对路径（基于相对路径配置）
     */
    private Path getMapsDirectoryPath() throws IOException {
        // 获取项目根目录
        Path projectRoot = Paths.get("").toAbsolutePath();
        System.out.println("项目根目录: " + projectRoot);

        // 构建完整路径
        Path targetPath = projectRoot.resolve(mapsDirectory).normalize();

        System.out.println("解析后Maps路径: " + targetPath);

        if (!Files.exists(targetPath)) {
            Files.createDirectories(targetPath);
            System.out.println("创建目录: " + targetPath);
        }

        return targetPath;
    }

    /**
     * 获取Maps目录的绝对路径
     */


    // 文件名安全验证（防止路径遍历攻击）
    private boolean isValidFileName(String fileName) {
        return !fileName.contains("..") &&           // 防止路径遍历
                !fileName.contains("/") &&            // 禁止路径分隔符
                !fileName.contains("\\") &&
                !fileName.contains(":") &&
                !fileName.contains("%00") &&          // 防止空字节攻击
                fileName.matches("[a-zA-Z0-9\\-_.]+"); // 只允许字母数字、下划线、破折号和点
    }



    @PostMapping("/pgm")
    public ResponseEntity<String> uploadPgmFile(
            @RequestParam("pgmFile") MultipartFile file,
            @RequestParam("mapName") String mapName) {

        try {
            Path mapsPath = getMapsDirectoryPath();
            // 确保地图目录存在
            Path mapPath = mapsPath.resolve(mapName);
            if (!Files.exists(mapPath)) {
                Files.createDirectories(mapPath);
            }

            // 确保 setting 目录存在
            Path settingPath = mapPath.resolve("setting");
            if (!Files.exists(settingPath)) {
                Files.createDirectories(settingPath);
            }

            // 保存文件
            Path targetPath = settingPath.resolve("map.pgm");
            file.transferTo(targetPath.toFile());
            System.out.println("文件保存成功: " + targetPath.toAbsolutePath());

            return ResponseEntity.ok("PGM文件保存成功: " + mapName);
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("PGM文件保存失败: " + e.getMessage());
        }
    }





    @GetMapping("/maps")
    public ResponseEntity<Object> getMapNamesWithPgmFiles() {
        try {
            // 获取地图目录路径
            Path mapsPath = getMapsDirectoryPath();

            // 检查目录是否存在
            if (!Files.exists(mapsPath)) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Collections.singletonMap("error", "地图目录不存在: " + mapsPath.toAbsolutePath()));
            }

            // 创建结果对象
            Map<String, Object> response = new HashMap<>();
            Map<String, Map<String, Object>> maps = new LinkedHashMap<>();

            // 获取所有文件和目录
            try (Stream<Path> stream = Files.list(mapsPath)) {
                stream.filter(Files::isDirectory)  // 只处理目录
                        .forEach(dir -> {
                            String mapName = dir.getFileName().toString();

                            // 检查PGM文件是否存在
                            Path pgmPath = dir.resolve("setting/map.pgm");
                            boolean pgmExists = Files.exists(pgmPath);

                            // 为每个地图创建PGM文件对象
                            Map<String, Object> pgmInfo = new HashMap<>();
                            pgmInfo.put("fileName", "map.pgm");
                            pgmInfo.put("exists", pgmExists);

                            // 如果文件存在，添加更多信息
                            if (pgmExists) {
                                try {
                                    pgmInfo.put("size", Files.size(pgmPath));
                                    pgmInfo.put("lastModified", Files.getLastModifiedTime(pgmPath).toMillis());
                                } catch (IOException e) {
                                    // 忽略错误，保持基本属性
                                }
                            }

                            maps.put(mapName, pgmInfo);
                        });
            }

            // 准备最终响应
            response.put("success", true);
            response.put("maps", maps);
            response.put("basePath", mapsPath.toString());

            return ResponseEntity.ok(response);

        } catch (IOException e) {
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Collections.singletonMap("error", "获取地图列表失败: " + e.getMessage()));
        }
    }




    @GetMapping(value = "/pgm/{mapName}")
    public ResponseEntity<Resource> getPgmFile(@PathVariable String mapName) {
        try {
            // 获取地图目录路径
            Path mapsPath = getMapsDirectoryPath();

            // 尝试两种可能的路径: /mapName/map.pgm 和 /mapName/setting/map.pgm
            Path pgmPath = mapsPath.resolve(mapName).resolve("map.pgm");
            if (!Files.exists(pgmPath)) {
                pgmPath = mapsPath.resolve(mapName).resolve("setting").resolve("map.pgm");
            }

            // 查找同目录下的 map.yaml
            Path yamlPath = pgmPath.getParent().resolve("map.yaml");

            // 检查文件是否存在
            if (!Files.exists(pgmPath)) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(null);
            }

            // 读取文件资源
            Resource resource = new InputStreamResource(Files.newInputStream(pgmPath));

            // 设置响应头（包含PGM文件）
            HttpHeaders headers = new HttpHeaders();
            headers.set(HttpHeaders.CONTENT_TYPE, "image/x-portable-greymap");
            headers.set(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=map.pgm");

            // 如果存在YAML文件，将内容Base64编码后添加到响应头
            if (Files.exists(yamlPath)) {
                byte[] yamlBytes = Files.readAllBytes(yamlPath);
                String base64Yaml = Base64.getEncoder().encodeToString(yamlBytes);
                headers.set("X-Map-Yaml", base64Yaml);
            }

            return ResponseEntity.ok()
                    .headers(headers)
                    .body(resource);

        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(null);
        }
    }



    @PostMapping("/yaml")
    public ResponseEntity<String> copyYamlFile(
            @RequestBody Map<String, String> request) {

        String sourceMapName = request.get("sourceMapName");
        String targetMapName = request.get("targetMapName");

        try {
            Path mapsPath = getMapsDirectoryPath();

            // 源YAML文件路径
            Path sourceYamlPath = mapsPath.resolve(sourceMapName).resolve("setting").resolve("map.yaml");

            // 目标YAML文件路径
            Path targetYamlPath = mapsPath.resolve(targetMapName).resolve("setting").resolve("map.yaml");

            // 确保目标目录存在
            Files.createDirectories(targetYamlPath.getParent());

            // 复制YAML文件
            if (Files.exists(sourceYamlPath)) {
                Files.copy(sourceYamlPath, targetYamlPath, StandardCopyOption.REPLACE_EXISTING);
                return ResponseEntity.ok("YAML文件复制成功");
            } else {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body("源YAML文件不存在: " + sourceYamlPath);
            }
        } catch (IOException e) {
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("YAML文件复制失败: " + e.getMessage());
        }
    }

}