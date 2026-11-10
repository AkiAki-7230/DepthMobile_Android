# 深度标定 - 快速开始

## 一句话说明

通过在4个已知距离采集深度数据，计算一个全局校正系数来提高深度精度。

## 5分钟上手

### 1. 编译安装（第一次）

```bash
cd /c/Users/wyn06/Desktop/DepthMobile
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 2. 采集数据（手机上）

1. 打开应用，点击右下角"标定"按钮
2. 按屏幕提示在 1m、2m、3m、5m 处各采集一次
3. 点击"完成标定"

### 3. 导出数据

```bash
adb pull /storage/emulated/0/Android/data/com.example.depthmobile/files/Documents/DepthCalibration/calibration_*.json .
```

### 4. 运行标定

```bash
python depth_calibration_tool.py calibration_20260125_143022.json
```

会自动更新 `app/src/main/assets/depth_metric_calibration.properties`

### 5. 重新安装

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

完成！现在深度读数应该更准确了。

## 采集技巧

✓ 目标在画面中心  
✓ 垂直对准，不倾斜  
✓ 光线充足  
✓ 用卷尺测量准确  

✗ 避免玻璃、镜子  
✗ 避免手机晃动  

## 验证效果

标定后，在不同距离测试:
- 误差 < 10cm: 较好（经验参考，需用留出距离实测确认）
- 误差 10-20cm: 可接受
- 误差 > 20cm: 建议重新采集或复检环境

## 详细文档

见 `CALIBRATION_GUIDE.md`
