    #include <jni.h>

    #include <android/log.h>
    #include <android/asset_manager_jni.h>
    #include <android/bitmap.h>

    #include <ncnn/cpu.h>
    #include <ncnn/gpu.h>
    #include <ncnn/net.h>

    #include <algorithm>
    #include <atomic>
    #include <chrono>
    #include <cmath>
    #include <cstring>
    #include <limits>
    #include <string>
    #include <vector>


    #define TAG "NCNN_TEST"
    #define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
    #define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)


    static ncnn::Net depthNet;
    static bool modelLoaded = false;
    static bool useVulkanBackend = false;
    static std::atomic<float> depthScaleCorrection{1.0f};

    namespace
    {
        constexpr int MODEL_INPUT_WIDTH = 384;
        constexpr int MODEL_INPUT_HEIGHT = 384;
        constexpr int MODEL_OUTPUT_SAMPLES = MODEL_INPUT_WIDTH * MODEL_INPUT_HEIGHT;
        constexpr float LETTERBOX_VALUE = 114.0f;
        constexpr float MIN_DEPTH_SCALE_CORRECTION = 0.25f;
        constexpr float MAX_DEPTH_SCALE_CORRECTION = 4.0f;
        constexpr const char* MODEL_PARAM_ASSET = "yolo26n_depth.param";
        constexpr const char* MODEL_BIN_ASSET = "yolo26n_depth.bin";
    }


    static jfloatArray createEmptyFloatArray(JNIEnv* env)
    {
        return env->NewFloatArray(0);
    }


    extern "C"
    JNIEXPORT jint JNICALL
    JNI_OnLoad(JavaVM*, void*)
    {
        const int result = ncnn::create_gpu_instance();
        LOGD("ncnn Vulkan instance result=%d", result);
        return JNI_VERSION_1_6;
    }


    extern "C"
    JNIEXPORT void JNICALL
    JNI_OnUnload(JavaVM*, void*)
    {
        depthNet.clear();
        modelLoaded = false;
        ncnn::destroy_gpu_instance();
    }



    extern "C"
    JNIEXPORT jstring JNICALL
    Java_com_example_depthmobile_NativeNcnn_initModel(
            JNIEnv* env,
            jobject,
            jobject assetManager)
    {
        LOGD("initialising YOLO26n-depth");

        AAssetManager* mgr =
                AAssetManager_fromJava(
                        env,
                        assetManager
                );

        if (mgr == nullptr)
        {
            LOGE("AssetManager is null");

            return env->NewStringUTF(
                    "asset manager failed"
            );
        }

        depthNet.clear();

        const int gpuCount = ncnn::get_gpu_count();
        useVulkanBackend = gpuCount > 0;
        const int cpuThreads = std::max(1, std::min(4, ncnn::get_big_cpu_count()));

        // Vulkan and FP16 storage provide the largest mobile speed-up while keeping
        // arithmetic in FP32 for stable metric-depth output. Devices without a usable
        // Vulkan driver automatically stay on the big CPU cores.
        depthNet.opt.use_vulkan_compute = useVulkanBackend;
        depthNet.opt.use_fp16_storage = useVulkanBackend;
        depthNet.opt.use_fp16_packed = true;
        depthNet.opt.use_fp16_arithmetic = false;
        depthNet.opt.num_threads = cpuThreads;

        const int paramRet =
                depthNet.load_param(
                        mgr,
                        MODEL_PARAM_ASSET
                );

        if (paramRet != 0)
        {
            LOGE(
                    "load_param failed, ret=%d",
                    paramRet
            );

            modelLoaded = false;

            return env->NewStringUTF(
                    "param failed"
            );
        }

        const int modelRet =
                depthNet.load_model(
                        mgr,
                        MODEL_BIN_ASSET
                );

        if (modelRet != 0)
        {
            LOGE(
                    "load_model failed, ret=%d",
                    modelRet
            );

            modelLoaded = false;

            return env->NewStringUTF(
                    "bin failed"
            );
        }

        modelLoaded = true;

        const char* backend = useVulkanBackend ? "Vulkan FP16 storage" : "CPU FP32";
        LOGD(
                "YOLO26n-depth loaded - backend=%s gpu_count=%d cpu_threads=%d input=384x384",
                backend,
                gpuCount,
                cpuThreads
        );

        const std::string status = std::string("YOLO26n-depth init OK - ") + backend;
        return env->NewStringUTF(status.c_str());
    }


    extern "C"
    JNIEXPORT jboolean JNICALL
    Java_com_example_depthmobile_NativeNcnn_setDepthScaleCorrection(
            JNIEnv*,
            jobject,
            jfloat correctionScale)
    {
        if (!std::isfinite(correctionScale) ||
            correctionScale < MIN_DEPTH_SCALE_CORRECTION ||
            correctionScale > MAX_DEPTH_SCALE_CORRECTION)
        {
            LOGE("invalid metric-depth correction scale=%.6f", correctionScale);
            return JNI_FALSE;
        }

        depthScaleCorrection.store(correctionScale, std::memory_order_release);
        LOGD("metric-depth correction scale=%.6f", correctionScale);
        return JNI_TRUE;
    }


    extern "C"
    JNIEXPORT jfloatArray JNICALL
    Java_com_example_depthmobile_NativeNcnn_runDepth(
            JNIEnv* env,
            jobject,
            jobject bitmap)
    {
        const auto runStarted = std::chrono::steady_clock::now();

        if (!modelLoaded)
        {
            LOGE("model not loaded");

            return createEmptyFloatArray(env);
        }

        if (bitmap == nullptr)
        {
            LOGE("bitmap is null");

            return createEmptyFloatArray(env);
        }

        AndroidBitmapInfo info{};

        const int infoRet =
                AndroidBitmap_getInfo(
                        env,
                        bitmap,
                        &info
                );

        if (infoRet != ANDROID_BITMAP_RESULT_SUCCESS)
        {
            LOGE(
                    "AndroidBitmap_getInfo failed, ret=%d",
                    infoRet
            );

            return createEmptyFloatArray(env);
        }

        if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888)
        {
            LOGE(
                    "unsupported bitmap format=%d, expected RGBA_8888=%d",
                    info.format,
                    ANDROID_BITMAP_FORMAT_RGBA_8888
            );

            return createEmptyFloatArray(env);
        }

        void* lockedPixels = nullptr;

        const int lockRet =
                AndroidBitmap_lockPixels(
                        env,
                        bitmap,
                        &lockedPixels
                );

        if (lockRet != ANDROID_BITMAP_RESULT_SUCCESS ||
            lockedPixels == nullptr)
        {
            LOGE(
                    "AndroidBitmap_lockPixels failed, ret=%d",
                    lockRet
            );

            return createEmptyFloatArray(env);
        }

        const int sourceWidth =
                static_cast<int>(info.width);

        const int sourceHeight =
                static_cast<int>(info.height);

        const int sourceStride =
                static_cast<int>(info.stride);

        const int packedRowBytes =
                sourceWidth * 4;

        /*
         * Android Bitmap 每行可能有额外 padding。
         * ncnn::from_pixels_resize 默认要求像素行紧密排列，
         * 因此先逐行复制，去掉 stride padding。
         */
        std::vector<unsigned char> packedRgba(
                static_cast<size_t>(sourceWidth) *
                static_cast<size_t>(sourceHeight) *
                4
        );

        const auto* sourcePixels =
                static_cast<const unsigned char*>(
                        lockedPixels
                );

        for (int y = 0; y < sourceHeight; ++y)
        {
            const unsigned char* sourceRow =
                    sourcePixels +
                    static_cast<size_t>(y) *
                    sourceStride;

            unsigned char* destinationRow =
                    packedRgba.data() +
                    static_cast<size_t>(y) *
                    packedRowBytes;

            std::memcpy(
                    destinationRow,
                    sourceRow,
                    packedRowBytes
            );
        }

        AndroidBitmap_unlockPixels(
                env,
                bitmap
        );

        /*
         * Match Ultralytics LetterBox preprocessing: preserve the camera aspect ratio,
         * centre the resized image in a 384x384 canvas, and fill unused pixels with 114.
         * Keeping the padding in the returned depth map also matches PreviewView's
         * FILL_CENTER crop, so the contours remain aligned with the camera preview.
         */
        const float resizeScale = std::min(
                static_cast<float>(MODEL_INPUT_WIDTH) / sourceWidth,
                static_cast<float>(MODEL_INPUT_HEIGHT) / sourceHeight
        );
        const int resizedWidth = std::max(
                1,
                std::min(
                        MODEL_INPUT_WIDTH,
                        static_cast<int>(std::round(sourceWidth * resizeScale))
                )
        );
        const int resizedHeight = std::max(
                1,
                std::min(
                        MODEL_INPUT_HEIGHT,
                        static_cast<int>(std::round(sourceHeight * resizeScale))
                )
        );

        ncnn::Mat resized =
                ncnn::Mat::from_pixels_resize(
                        packedRgba.data(),
                        ncnn::Mat::PIXEL_RGBA2RGB,
                        sourceWidth,
                        sourceHeight,
                        resizedWidth,
                        resizedHeight
                );

        if (resized.empty())
        {
            LOGE("resized input Mat is empty");

            return createEmptyFloatArray(env);
        }

        static bool cpuRuntimeConfigured = false;
        if (!useVulkanBackend && !cpuRuntimeConfigured)
        {
            ncnn::set_cpu_powersave(2);
            ncnn::set_omp_dynamic(0);
            cpuRuntimeConfigured = true;
        }

        const int horizontalPadding = MODEL_INPUT_WIDTH - resizedWidth;
        const int verticalPadding = MODEL_INPUT_HEIGHT - resizedHeight;
        const int leftPadding = horizontalPadding / 2;
        const int rightPadding = horizontalPadding - leftPadding;
        const int topPadding = verticalPadding / 2;
        const int bottomPadding = verticalPadding - topPadding;

        ncnn::Mat input;
        ncnn::copy_make_border(
                resized,
                input,
                topPadding,
                bottomPadding,
                leftPadding,
                rightPadding,
                ncnn::BORDER_CONSTANT,
                LETTERBOX_VALUE
        );

        if (input.empty() ||
            input.w != MODEL_INPUT_WIDTH ||
            input.h != MODEL_INPUT_HEIGHT)
        {
            LOGE(
                    "letterbox failed: got=%dx%d expected=%dx%d",
                    input.w,
                    input.h,
                    MODEL_INPUT_WIDTH,
                    MODEL_INPUT_HEIGHT
            );

            return createEmptyFloatArray(env);
        }

        /*
         * Ultralytics depth preprocessing only scales RGB bytes from [0, 255] to
         * [0, 1]. ImageNet mean/std normalisation used by Depth Anything must not
         * be applied to YOLO26n-depth.
         */
        const float normValues[3] =
                {
                        1.0f / 255.0f,
                        1.0f / 255.0f,
                        1.0f / 255.0f
                };

        input.substract_mean_normalize(
                nullptr,
                normValues
        );

        /*
         * 检查模型实际收到的三个通道。
         * 正常画面下，每个通道的 min/max 应明显不同。
         */
        const auto inferenceStarted = std::chrono::steady_clock::now();

        ncnn::Extractor extractor =
                depthNet.create_extractor();

        extractor.set_light_mode(true);

        const int inputRet =
                extractor.input(
                        "in0",
                        input
                );

        if (inputRet != 0)
        {
            LOGE(
                    "extractor.input failed, ret=%d",
                    inputRet
            );

            return createEmptyFloatArray(env);
        }

        ncnn::Mat output;

        const int extractRet =
                extractor.extract(
                        "out0",
                        output
                );

        if (extractRet != 0)
        {
            LOGE(
                    "extract out0 failed, ret=%d",
                    extractRet
            );

            return createEmptyFloatArray(env);
        }

        if (output.empty())
        {
            LOGE("output is empty");

            return createEmptyFloatArray(env);
        }

        /*
         * CPU FP32 测试下，期望 elemsize=4、elempack=1。
         */
        if (output.elemsize != sizeof(float) ||
            output.elempack != 1)
        {
            LOGE(
                    "unexpected output format: elemsize=%zu elempack=%d",
                    output.elemsize,
                    output.elempack
            );

            return createEmptyFloatArray(env);
        }

        const size_t outputSize =
                output.total();

        if (outputSize != MODEL_OUTPUT_SAMPLES)
        {
            LOGE(
                    "unexpected output shape: dims=%d w=%d h=%d c=%d total=%zu",
                    output.dims,
                    output.w,
                    output.h,
                    output.c,
                    outputSize
            );

            return createEmptyFloatArray(env);
        }

        const auto inferenceFinished = std::chrono::steady_clock::now();

        auto* outputData =
                static_cast<float*>(
                        output.data
                );
        const float invalidDepth = std::numeric_limits<float>::quiet_NaN();
        const float activeDepthScaleCorrection =
                depthScaleCorrection.load(std::memory_order_acquire);
        for (int y = 0; y < MODEL_INPUT_HEIGHT; ++y)
        {
            for (int x = 0; x < MODEL_INPUT_WIDTH; ++x)
            {
                const size_t outputIndex =
                        static_cast<size_t>(y) * MODEL_INPUT_WIDTH + x;
                if (x < leftPadding ||
                    x >= MODEL_INPUT_WIDTH - rightPadding ||
                    y < topPadding ||
                    y >= MODEL_INPUT_HEIGHT - bottomPadding)
                {
                    outputData[outputIndex] = invalidDepth;
                }
                else if (activeDepthScaleCorrection != 1.0f &&
                         std::isfinite(outputData[outputIndex]) &&
                         outputData[outputIndex] > 0.0f)
                {
                    outputData[outputIndex] *= activeDepthScaleCorrection;
                }
            }
        }

        jfloatArray result =
                env->NewFloatArray(
                        static_cast<jsize>(
                                outputSize
                        )
                );

        if (result == nullptr)
        {
            LOGE("NewFloatArray failed");

            return createEmptyFloatArray(env);
        }

        env->SetFloatArrayRegion(
                result,
                0,
                static_cast<jsize>(
                        outputSize
                ),
                outputData
        );

        static unsigned int frameCounter = 0;
        ++frameCounter;
        if (frameCounter == 1 || frameCounter % 30 == 0)
        {
            const auto runFinished = std::chrono::steady_clock::now();
            const double inferenceMs = std::chrono::duration<double, std::milli>(
                    inferenceFinished - inferenceStarted
            ).count();
            const double totalMs = std::chrono::duration<double, std::milli>(
                    runFinished - runStarted
            ).count();
            LOGD(
                    "depth frame=%u backend=%s inference=%.1fms native_total=%.1fms",
                    frameCounter,
                    useVulkanBackend ? "Vulkan" : "CPU",
                    inferenceMs,
                    totalMs
            );
        }

        return result;
    }
