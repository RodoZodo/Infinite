#ifndef INFINITE_EVICT_EVICTION
#define INFINITE_EVICT_EVICTION


// Shared buffers and log-age bucketing for the 3 pass LRU geometry eviction pipeline

#import <infinite:common/node.glsl>

const uint AGE_BUCKETS = 1024u;
const uint NOT_EVICTABLE = AGE_BUCKETS;
const uint NEVER = 0xFFFFFFFFu;

layout(binding = 0, std430) restrict readonly buffer Nodes {
    uvec4 nodes[];
};

// Frame each node was last selected for drawing
layout(binding = 1, std430) restrict buffer LastSeen {
    uint lastSeen[];
};

// Cleared to zero before the passes
layout(binding = 2, std430) restrict buffer Selection {
    uint thresholdBucket;
    uint olderCount;
    uint olderTaken;
    uint thresholdTaken;
    uint histogram[AGE_BUCKETS];
};

layout(location = 0) uniform uint frame;
layout(location = 1) uniform uint nodeCount;
layout(location = 2) uniform uint seenBefore;
layout(location = 3) uniform uint batch;

uint ageBucket(uint nodeId) {
    uint seen = lastSeen[nodeId];
    if (seen == NEVER || seen >= seenBefore) return NOT_EVICTABLE;
    uvec4 node = nodes[nodeId];
    if (nodeUnused(node) || nodeRequested(node) || lodLevel(node.xy) == TOP_LEVEL) return NOT_EVICTABLE;
    uint mesh = nodeMesh(node);
    if (mesh == NODE_NONE || mesh == NODE_EMPTY) return NOT_EVICTABLE;
    float age = float(max(int(frame - seen), 0));
    return min(uint(log2(age + 1.0) * 64.0), AGE_BUCKETS - 1u);
}
#endif
