# 将dist目录打包到JAR的方案

## 方案一：Spring Boot项目（推荐）

### 1. 确保目录结构正确
```
<app-dir>/
├── pom.xml 或 build.gradle
├── src/
│   └── main/
│       ├── java/           # Java源代码
│       └── resources/
│           ├── application.properties
│           └── static/     # 静态资源目录
│               ├── dist/   # Webpack构建输出
│               └── ...     # 其他静态资源
└── ...
```

### 2. Maven配置 (pom.xml)
在pom.xml中添加前端构建插件：

```xml
<build>
    <plugins>
        <!-- Spring Boot Maven插件 -->
        <plugin>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-maven-plugin</artifactId>
        </plugin>
        
        <!-- Frontend Maven插件 - 在构建JAR前执行npm构建 -->
        <plugin>
            <groupId>com.github.eirslett</groupId>
            <artifactId>frontend-maven-plugin</artifactId>
            <version>1.12.1</version>
            <configuration>
                <workingDirectory>src/main/resources/static</workingDirectory>
                <installDirectory>target</installDirectory>
            </configuration>
            <executions>
                <execution>
                    <id>install node and npm</id>
                    <goals>
                        <goal>install-node-and-npm</goal>
                    </goals>
                    <configuration>
                        <nodeVersion>v16.14.0</nodeVersion>
                        <npmVersion>8.5.0</npmVersion>
                    </configuration>
                </execution>
                <execution>
                    <id>npm install</id>
                    <goals>
                        <goal>npm</goal>
                    </goals>
                </execution>
                <execution>
                    <id>npm run build</id>
                    <goals>
                        <goal>npm</goal>
                    </goals>
                    <configuration>
                        <arguments>run build</arguments>
                    </configuration>
                </execution>
            </executions>
        </plugin>
    </plugins>
</build>
```

### 3. Gradle配置 (build.gradle.kts)
```kotlin
plugins {
    // ...其他插件
    id("com.github.node-gradle.node") version "3.5.1"
}

// 配置Node.js和npm
node {
    version.set("16.14.0")
    npmVersion.set("8.5.0")
    download.set(true)
    workDir.set(file("${project.buildDir}/nodejs"))
    npmWorkDir.set(file("${project.buildDir}/npm"))
}

// 前端构建任务
val npmBuild = tasks.named<NpmTask>("npm_build") {
    description = "Build the frontend project"
    group = "build"
    args.set(listOf("run", "build"))
    workingDir.set(file("src/main/resources/static"))
    dependsOn(tasks.named("npm_install"))
}

// 确保在构建JAR前执行前端构建
tasks.named<Jar>("jar") {
    dependsOn(npmBuild)
}
```

### 4. 构建和运行
```bash
# Maven
mvn clean package
java -jar target/RobotSystem-*.jar

# Gradle
./gradlew build
java -jar build/libs/RobotSystem-*.jar
```

## 方案二：手动复制dist目录

如果不想修改构建配置，可以手动复制：

1. 构建前端资源：
```bash
cd src/main/resources/static
npm run build
```

2. 复制dist目录到Spring Boot项目的resources/static目录下（如果不在正确位置）

3. 构建JAR：
```bash
mvn clean package
# 或
./gradlew build
```

## 方案三：使用CI/CD流水线

在CI/CD中添加前端构建步骤：

```yaml
# GitHub Actions示例
name: Build and Package
on:
  push:
    branches: [ main ]

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
    - uses: actions/checkout@v2
    
    # 设置Node.js环境
    - name: Setup Node.js
      uses: actions/setup-node@v2
      with:
        node-version: '16'
        
    # 构建前端
    - name: Build Frontend
      run: |
        cd src/main/resources/static
        npm install
        npm run build
        
    # 设置Java环境并构建后端
    - name: Setup Java
      uses: actions/setup-java@v2
      with:
        java-version: '11'
        distribution: 'adopt'
        
    - name: Build with Maven
      run: mvn clean package
```

## 访问静态资源

打包到JAR后，静态资源可以通过以下URL访问：
- 基础路径：`http://localhost:8080/dist/`
- 具体文件：`http://localhost:8080/dist/src/components/outdoor-map/outdoor-map.html`

## 注意事项

1. **路径问题**：确保HTML中的资源路径使用相对路径，以便在JAR中正确访问
2. **缓存问题**：考虑添加版本号或哈希值到静态资源URL，避免缓存问题
3. **大小问题**：大型静态资源会增加JAR文件大小，影响启动速度
4. **开发环境**：开发时可以使用热重载，生产环境使用打包后的静态资源