# APK 编译说明

## 方法1: 使用编译脚本（推荐）

双击运行项目根目录下的 `build_apk.bat` 文件即可自动编译。

## 方法2: 手动命令行编译

打开命令提示符（CMD）或 PowerShell，切换到项目目录：

```bash
cd C:\Users\wyn06\Desktop\DepthMobile
```

### Windows:
```bash
# 清理旧文件
gradlew.bat clean

# 编译 Debug 版本
gradlew.bat assembleDebug

# 编译 Release 版本（需要签名配置）
gradlew.bat assembleRelease
```

### Linux/Mac:
```bash
# 清理旧文件
./gradlew clean

# 编译 Debug 版本
./gradlew assembleDebug

# 编译 Release 版本
./gradlew assembleRelease
```

## 生成的APK位置

编译成功后，APK文件位于：

**Debug版本**:
```
app/build/outputs/apk/debug/app-debug.apk
```

**Release版本**:
```
app/build/outputs/apk/release/app-release.apk
```

## 安装到设备

### 方法1: ADB命令（推荐）

确保设备已连接并开启USB调试：

```bash
# 检查设备连接
adb devices

# 安装APK
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

参数说明：
- `-r`: 覆盖安装（保留数据）
- `-t`: 允许安装测试APK

### 方法2: 手动安装

1. 将APK文件复制到手机
2. 在手机上找到文件并点击安装
3. 允许"未知来源"安装（如需要）

## 常见问题

### 问题1: "SDK位置未配置"

**解决方案**:

创建或编辑 `local.properties` 文件：

```properties
sdk.dir=C\:\\Users\\wyn06\\AppData\\Local\\Android\\Sdk
```

### 问题2: "找不到 Java"

**解决方案**:

1. 确保已安装 JDK 17 或更高版本
2. 设置 JAVA_HOME 环境变量
3. 重启命令行

### 问题3: "Gradle 下载慢"

**解决方案**:

编辑 `gradle/wrapper/gradle-wrapper.properties`，使用国内镜像：

```properties
distributionUrl=https\://mirrors.cloud.tencent.com/gradle/gradle-8.7-bin.zip
```

### 问题4: "NDK 未找到"

**解决方案**:

本项目使用 NCNN，需要 NDK。在 Android Studio 中：
1. Tools → SDK Manager → SDK Tools
2. 勾选 NDK (Side by side)
3. 点击 Apply 下载

或在 `local.properties` 中配置：
```properties
ndk.dir=C\:\\Users\\wyn06\\AppData\\Local\\Android\\Sdk\\ndk\\26.1.10909125
```

### 问题5: "内存不足"

**解决方案**:

编辑 `gradle.properties`，增加堆内存：

```properties
org.gradle.jvmargs=-Xmx4096m -XX:MaxMetaspaceSize=512m
```

## 编译选项

### Debug vs Release

| 版本 | 用途 | 特点 |
|------|------|------|
| Debug | 开发测试 | 可调试，未混淆，文件较大 |
| Release | 正式发布 | 已混淆，已优化，需签名 |

### 编译变体

```bash
# 查看所有可用的构建任务
gradlew.bat tasks --all | findstr "assemble"

# 常用任务：
# assembleDebug - Debug版本
# assembleRelease - Release版本
# installDebug - 编译并安装Debug版本
# uninstallDebug - 卸载Debug版本
```

## 签名配置（Release版本）

Release版本需要签名。在 `app/build.gradle` 中配置：

```gradle
android {
    signingConfigs {
        release {
            storeFile file("your-keystore.jks")
            storePassword "your-password"
            keyAlias "your-alias"
            keyPassword "your-password"
        }
    }
    buildTypes {
        release {
            signingConfig signingConfigs.release
            minifyEnabled true
            proguardFiles getDefaultProguardFile('proguard-android-optimize.txt'), 'proguard-rules.pro'
        }
    }
}
```

## 生成签名密钥

```bash
keytool -genkey -v -keystore my-release-key.jks -keyalg RSA -keysize 2048 -validity 10000 -alias my-key-alias
```

## 检查APK信息

```bash
# 查看APK签名信息
keytool -printcert -jarfile app/build/outputs/apk/debug/app-debug.apk

# 查看APK包含的文件
jar tf app/build/outputs/apk/debug/app-debug.apk
```

## 优化建议

1. **首次编译会较慢** - Gradle需要下载依赖
2. **使用 `--offline` 离线编译** - 如已下载全部依赖
3. **使用 `--parallel` 并行编译** - 加速构建
4. **定期清理** - `gradlew.bat clean` 清理缓存

```bash
# 完整优化命令
gradlew.bat clean assembleDebug --parallel --offline
```

## 相关文档

- [Android Gradle插件文档](https://developer.android.com/build)
- [Gradle构建指南](https://docs.gradle.org/)
- [APK签名指南](https://developer.android.com/studio/publish/app-signing)

---

**最后更新**: 2026-10-25
