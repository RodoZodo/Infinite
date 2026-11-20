#ifndef INFINITE_COMMON_SCAN
#define INFINITE_COMMON_SCAN


// Workgroup wide exclusive prefix sum for SCAN_SIZE invocations with a max of 4096
// Under SUBGROUP_SCAN, scanIndex() orders elements by subgroup since Vulkan subgroup fill order is unspecified

#ifdef SUBGROUP_SCAN
#extension GL_KHR_shader_subgroup_basic : require
#extension GL_KHR_shader_subgroup_arithmetic : require

shared uint subgroupBase[SCAN_SIZE / 16];

uint scanIndex() {
    return gl_SubgroupID * gl_SubgroupSize + gl_SubgroupInvocationID;
}

uint workgroupExclusiveSum(uint value) {
    uint inclusive = subgroupInclusiveAdd(value);
    if (gl_SubgroupInvocationID == gl_SubgroupSize - 1u) subgroupBase[gl_SubgroupID] = inclusive;
    barrier();
    if (gl_SubgroupID == 0u) {
        uint lane = gl_SubgroupInvocationID;
        uint total = lane < gl_NumSubgroups ? subgroupBase[lane] : 0u;
        uint base = subgroupExclusiveAdd(total);
        if (lane < gl_NumSubgroups) subgroupBase[lane] = base;
    }
    barrier();
    return subgroupBase[gl_SubgroupID] + inclusive - value;
}
#else
shared uint scanPartial[2][SCAN_SIZE];

uint scanIndex() {
    return gl_LocalInvocationIndex;
}

uint workgroupExclusiveSum(uint value) {
    uint i = scanIndex();
    uint current = 0u;
    scanPartial[0][i] = value;
    barrier();
    for (uint offset = 1u; offset < SCAN_SIZE; offset <<= 1) {
        uint sum = scanPartial[current][i] + (i >= offset ? scanPartial[current][i - offset] : 0u);
        scanPartial[current ^ 1u][i] = sum;
        current ^= 1u;
        barrier();
    }
    return scanPartial[current][i] - value;
}
#endif
#endif
