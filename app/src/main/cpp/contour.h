#ifndef CONTOUR_H
#define CONTOUR_H


#ifdef __cplusplus
extern "C" {
#endif


void generateContourImage(
        float *depth,
        int width,
        int height,
        int levels,
        unsigned char *output
);


#ifdef __cplusplus
}
#endif


#endif