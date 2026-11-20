// Marks a section visible this frame. Early tests make only pixels that pass the depth test run this.

layout(early_fragment_tests) in;

#import <infinite:common/frame.glsl>

layout(binding = 2, std430) restrict writeonly buffer VisibleNow {
    uint visibleNow[];
};

layout(location = 0) flat in uint sectionId;

void main() {
    visibleNow[sectionId] = frameId;
}
