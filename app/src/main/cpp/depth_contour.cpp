#include "contour.h"

#include <opencv2/opencv.hpp>
#include <vector>
#include <cstring>


using namespace cv;
using namespace std;



void generateContourImage(
        float *depth,
        int width,
        int height,
        int levels,
        unsigned char *output
)
{

    /*
     * depth:
     * width*height float数组
     *
     * 输出:
     * width*height*4 RGBA
     */


    if(depth == nullptr ||
       output == nullptr ||
       width <=0 ||
       height <=0)
    {
        return;
    }


    if(levels < 2)
    {
        levels = 2;
    }



    //--------------------------------
    // 1. 创建深度Mat
    //--------------------------------

    Mat depthMat(
            height,
            width,
            CV_32FC1,
            depth
    );



    //--------------------------------
    // 2. 获取深度范围
    //--------------------------------

    double minDepth;
    double maxDepth;


    minMaxLoc(
            depthMat,
            &minDepth,
            &maxDepth
    );


    if(maxDepth-minDepth < 0.001)
    {
        memset(
                output,
                0,
                width*height*4
        );

        return;
    }



    //--------------------------------
    // 3. 创建RGBA透明背景
    //--------------------------------

    Mat rgba =
            Mat::zeros(
                    height,
                    width,
                    CV_8UC4
            );



    //--------------------------------
    // 4. 生成多层等深线
    //--------------------------------

    for(int i=1;i<levels;i++)
    {

        float levelValue =
                static_cast<float>(
                        minDepth +
                        (maxDepth-minDepth)
                        *
                        i
                        /
                        levels
                );



        Mat mask;


        compare(
                depthMat,
                levelValue,
                mask,
                CMP_GT
        );



        vector<vector<Point>> contours;


        findContours(
                mask,
                contours,
                RETR_EXTERNAL,
                CHAIN_APPROX_SIMPLE
        );



        if(contours.empty())
            continue;



        drawContours(
                rgba,
                contours,
                -1,
                Scalar(
                        0,      // B
                        255,    // G
                        0,      // R
                        255     // A
                ),
                1,
                LINE_AA
        );

    }



    //--------------------------------
    // 5. 复制RGBA给Android
    //--------------------------------

    memcpy(
            output,
            rgba.data,
            width*height*4
    );

}