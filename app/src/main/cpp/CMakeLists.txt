cmake_minimum_required(VERSION 3.22.1)


project(depthmobile)



# OpenCV

set(OpenCV_DIR
        ${CMAKE_SOURCE_DIR}/../../../../sdk/native/jni
)

find_package(OpenCV REQUIRED)




# ncnn


set(NCNN_ROOT
        ${CMAKE_SOURCE_DIR}/../../../../third_party/ncnn/arm64-v8a
)



add_library(
        ncnn
        SHARED
        IMPORTED
)


set_target_properties(
        ncnn

        PROPERTIES

        IMPORTED_LOCATION
        ${NCNN_ROOT}/lib/libncnn.so

        INTERFACE_INCLUDE_DIRECTORIES
        ${NCNN_ROOT}/include

)




# JNI


add_library(

        depth_contour

        SHARED

        depth_contour.cpp

        CameraGeometry.cpp

        NativeCalibration.cpp

        NativeContour.cpp

        NativeNcnn.cpp

)




find_library(
        log-lib
        log
)


find_library(
        jnigraphics-lib
        jnigraphics
)



target_link_libraries(

        depth_contour

        ncnn

        ${OpenCV_LIBS}

        ${log-lib}

        ${jnigraphics-lib}

)
