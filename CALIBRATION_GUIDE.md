# 深度标定工具使用指南（方案1）

## 概述

本工具通过在已知距离采集深度数据，计算一个全局的 `correctionScale` 系数来校正深度估计误差。

## 工作原理

1. **数据采集**: 在固定距离（1m、2m、3m、5m）拍摄物体，记录模型输出的深度值
2. **计算校正系数**: `correctionScale = 真实距离 / 测量距离`
3. **应用校正**: 最终深度 = 原始深度 × correctionScale

## 使用步骤

### 第一步：准备工作

1. 需要准备:
   - 一把卷尺或激光测距仪
   - 平坦的地面
   - 一个清晰的目标物体（如墙面、门、箱子等）

2. 找一个光线充足的环境

### 第二步：采集数据

1. 打开 DepthMobile 应用
2. 点击右下角的"标定"按钮
3. 进入标定界面后:
   - 屏幕顶部显示当前需要的距离（如 1.0m）
   - 屏幕中心有黄色十字标记

4. 对于每个距离:
   ```
   a. 用卷尺测量并标记该距离位置
   b. 将目标物体放在该位置（或人站在该位置面对墙）
   c. 手机对准目标，使目标位于画面中心的十字处
   d. 保持稳定2-3秒
   e. 点击"采集数据"按钮
   f. 等待提示"已采集: X.XXm"
   ```

5. 完成4个距离的采集后，点击"完成标定"

6. 数据会自动保存到:
   ```
   /storage/emulated/0/Android/data/com.example.depthmobile/files/Documents/DepthCalibration/calibration_YYYYMMDD_HHMMSS.json
   ```

### 第三步：连接手机导出数据

#### Windows:

```bash
# 使用USB连接手机
adb pull /storage/emulated/0/Android/data/com.example.depthmobile/files/Documents/DepthCalibration/calibration_20260125_143022.json .
```

#### Mac/Linux:

```bash
adb pull /storage/emulated/0/Android/data/com.example.depthmobile/files/Documents/DepthCalibration/calibration_20260125_143022.json .
```

或者直接通过文件管理器:
1. 打开手机的文件管理器
2. 进入 `文档/DepthCalibration/`
3. 通过蓝牙、邮件或云盘发送JSON文件到电脑

### 第四步：运行标定工具

1. 将JSON文件放到 `DepthMobile` 项目目录

2. 运行Python脚本:

```bash
cd /c/Users/wyn06/Desktop/DepthMobile
python depth_calibration_tool.py calibration_20260125_143022.json
```

3. 工具会输出:
```
============================================================
深度标定工具 - 方案1
============================================================
设备: Google Pixel 6
时间: 20260125_143022
样本数: 4

样本详情:
  样本1: 目标=1.00m, 测量=0.85m, 比例=1.1765
  样本2: 目标=2.00m, 测量=1.72m, 比例=1.1628
  样本3: 目标=3.00m, 测量=2.58m, 比例=1.1628
  样本4: 目标=5.00m, 测量=4.31m, 比例=1.1601

计算结果:
  平均比例: 1.1656
  中位数比例: 1.1628
  标准差: 0.0068

标定后误差:
  平均绝对误差(MAE): 0.023m
  均方根误差(RMSE): 0.028m
  最大误差: 0.043m

各距离误差:
  目标=1.00m, 校正后=0.99m, 误差=-0.013m
  目标=2.00m, 校正后=2.00m, 误差=+0.000m
  目标=3.00m, 校正后=3.00m, 误差=-0.001m
  目标=5.00m, 校正后=5.01m, 误差=+0.014m

✓ 已更新配置文件: app/src/main/assets/depth_metric_calibration.properties
  correction_scale = 1.162791
  sample_count = 4

============================================================
标定完成！
============================================================

下一步:
1. 重新编译并安装应用
2. 在不同距离测试深度准确性
3. 如果需要，重新标定
```

### 第五步：重新编译应用

```bash
cd /c/Users/wyn06/Desktop/DepthMobile
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 第六步：验证标定效果

1. 打开应用
2. 在不同距离测试深度读数
3. 检查误差是否在可接受范围内（经验目标 ±10~20cm，具体以留出距离实测为准）

## 注意事项

### 采集时的要求:

✓ **正确的做法:**
- 目标物体垂直于地面
- 相机与目标平行（不要倾斜）
- 目标物体有清晰的纹理或边缘
- 光线充足均匀
- 保持手机稳定

✗ **错误的做法:**
- 对着玻璃、镜子、反光表面
- 目标物体太小或太大
- 光线太暗或逆光
- 手机晃动
- 目标不在画面中心

### 重新标定的情况:

需要重新标定的情况:
- 更换了不同型号的手机
- 应用更新了深度模型
- 发现深度误差明显增大
- 第一次标定数据质量不好（采集时画面深度波动超过 ±12%，界面会提示重试）

## 常见问题

**Q: 为什么用 1m / 2m / 3m / 5m？**
A: 覆盖日常避障的主要区间。工具至少需要 3 个不同距离，并会用留一距离验证检查远近距离是否一致。

**Q: 只采 3 个点可以吗？**
A: 可以，但 4 个点能让留一距离验证更可靠。采集界面要求完成全部 4 个距离。

**Q: 标定后精度能到多少？**
A: 取决于设备、光线和距离。不要预设固定值，请用未参与拟合的距离（如 1.5m / 4m）实测 MAE；工具在验证不通过时会保持 1.0，不会强行应用。

**Q: 需要重新标定吗？**
A: 更换设备、更新深度模型或发现系统性偏差时重新标定。重复标定是安全的，不会叠加旧系数。

## 数据格式说明

JSON文件格式:

```json
{
  "device_manufacturer": "Google",
  "device_model": "Pixel 6",
  "timestamp": "20260125_143022",
  "sample_count": 4,
  "applied_correction_scale": 1.0,
  "samples": [
    {
      "target_distance_m": 1.0,
      "measured_depth_m": 0.85,
      "center_x": 192,
      "center_y": 192,
      "timestamp_ms": 1706186422123,
      "frame_count": 20,
      "relative_spread": 0.04
    },
    ...
  ]
}
```

## 故障排除

### 问题1: "无法获取深度数据"

解决方法:
- 确保目标物体在画面中心
- 增加环境光线
- 更换更清晰的目标物体
- 重启应用

### 问题2: 标定后误差仍然很大

可能原因:
- 采集时测量距离不准确
- 目标物体不合适（太暗、太亮、反光）
- 采集时手机晃动
- 不同距离的采集环境差异太大

解决方法:
- 重新仔细采集数据
- 使用激光测距仪确保距离准确
- 选择更好的采集环境

### 问题3: "adb: device not found"

解决方法:
```bash
# 检查设备连接
adb devices

# 如果没有设备，检查:
# 1. USB线是否连接
# 2. 手机是否开启USB调试
# 3. 是否授权电脑进行USB调试
```

## 高级用法

### 多次标定取平均

如果想获得更准确的结果，可以重复标定3次，然后手动平均:

```bash
python depth_calibration_tool.py calibration_run1.json
python depth_calibration_tool.py calibration_run2.json
python depth_calibration_tool.py calibration_run3.json

# 假设得到的 correctionScale 分别是: 1.162, 1.158, 1.165
# 平均值 = (1.162 + 1.158 + 1.165) / 3 = 1.162
```

然后手动编辑 `depth_metric_calibration.properties`:

```properties
correction_scale=1.162000
source=manual_calibration_averaged
sample_count=12
held_out_devices=0
```

### 查看历史标定数据

所有标定记录都保存在手机上:

```bash
adb shell ls -lh /storage/emulated/0/Android/data/com.example.depthmobile/files/Documents/DepthCalibration/
```

可以对比不同时间、不同环境的标定结果。

## 技术原理

### 为什么使用中位数而不是平均值？

中位数对异常值更鲁棒。如果某一个距离的测量出现较大误差，不会显著影响最终结果。

### 留一距离验证（自动门禁）

工具会依次留出一个距离，用其余距离拟合系数，再在留出的距离上评估。只有满足以下条件才采纳候选系数：

- `delta1` 不下降，且 `AbsRel` 不上升；
- 至少有一项指标严格改善；
- 候选系数落在 0.25 ~ 4.0 内。

否则写入 `1.0`（恒等校正），并在输出中说明 `identity_distance_cv_rejected`。

### 重复标定不会叠加系数

应用在标定期间会把原生校正系数临时复位为 1.0，导出的 JSON 中记录 `applied_correction_scale`；工具据此把测量值还原为模型原始输出后再拟合。因此可以在同一台设备上反复标定，不会出现"越标越偏"。

### correctionScale 的合理范围

一般在 0.8 ~ 1.5 之间:
- < 0.8: 可能采集数据有问题，或模型输出异常
- 0.8 ~ 1.2: 正常范围
- 1.2 ~ 1.5: 模型系统性低估深度
- \> 1.5: 数据质量有问题，需要重新采集

### 局限性

此方案的局限:
- **单一系数**: 无法修正非线性误差（远处误差 ≠ 近处误差）
- **设备特异性**: 每个设备需要单独标定
- **环境依赖**: 室内外、光线条件会影响效果

如果发现不同距离误差差异很大（如1m准确但5m不准），考虑使用方案2（分段校正）。

## 文件说明

```
DepthMobile/
├── app/src/main/
│   ├── assets/
│   │   └── depth_metric_calibration.properties  # 标定配置文件
│   ├── java/com/example/depthmobile/
│   │   ├── CalibrationActivity.kt               # 标定UI
│   │   └── CalibrationDepthAnalyzer.kt          # 深度数据提取
│   └── res/layout/
│       └── activity_calibration.xml             # 标定界面布局
├── depth_calibration_tool.py                    # 标定计算工具
└── CALIBRATION_GUIDE.md                         # 本文档
```

## 联系与反馈

如有问题或建议，请在项目issues中反馈。
