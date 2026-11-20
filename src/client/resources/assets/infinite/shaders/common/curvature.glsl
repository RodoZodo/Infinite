#ifndef INFINITE_COMMON_CURVATURE
#define INFINITE_COMMON_CURVATURE

// Computes the Earth curvature drop past the flat vanilla terrain boundary

float curvatureDrop(vec2 fromCamera, float drop, float start) {
    float past = max(length(fromCamera) - start, 0.0);
    return drop * past * past;
}
#endif
