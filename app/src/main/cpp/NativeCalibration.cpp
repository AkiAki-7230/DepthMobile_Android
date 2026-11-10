#include <jni.h>
#include <android/bitmap.h>

#include <algorithm>

#include "CameraGeometry.h"

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_example_depthmobile_NativeCalibration_configure(
        JNIEnv* env,
        jobject,
        jint referenceWidth,
        jint referenceHeight,
        jfloat fx,
        jfloat fy,
        jfloat cx,
        jfloat cy,
        jfloatArray distortionArray) {
    if (distortionArray == nullptr || env->GetArrayLength(distortionArray) < 5) return JNI_FALSE;
    float values[5]{};
    env->GetFloatArrayRegion(distortionArray, 0, 5, values);
    return camera_geometry::configure(
            referenceWidth,
            referenceHeight,
            fx,
            fy,
            cx,
            cy,
            {values[0], values[1], values[2], values[3], values[4]})
            ? JNI_TRUE
            : JNI_FALSE;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_example_depthmobile_NativeCalibration_undistortInPlace(
        JNIEnv* env,
        jobject,
        jobject bitmap) {
    if (bitmap == nullptr) return JNI_FALSE;
    AndroidBitmapInfo info{};
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
        info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        return JNI_FALSE;
    }
    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS ||
        pixels == nullptr) {
        return JNI_FALSE;
    }
    cv::Mat rgba(
            static_cast<int>(info.height),
            static_cast<int>(info.width),
            CV_8UC4,
            pixels,
            static_cast<size_t>(info.stride));
    const bool success = camera_geometry::undistortRgbaInPlace(&rgba);
    AndroidBitmap_unlockPixels(env, bitmap);
    return success ? JNI_TRUE : JNI_FALSE;
}

extern "C"
JNIEXPORT jfloatArray JNICALL
Java_com_example_depthmobile_NativeCalibration_pixelRay(
        JNIEnv* env,
        jobject,
        jfloat x,
        jfloat y,
        jint width,
        jint height) {
    const cv::Vec3f ray = camera_geometry::rayForUndistortedPixel(
            x, y, width, height, true);
    const float values[3] = {ray[0], ray[1], ray[2]};
    jfloatArray result = env->NewFloatArray(3);
    if (result != nullptr) env->SetFloatArrayRegion(result, 0, 3, values);
    return result;
}
