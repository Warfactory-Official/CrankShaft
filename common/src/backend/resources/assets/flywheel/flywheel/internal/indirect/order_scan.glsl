// One workgroup compacts one model's visible instances in page order, 32 instances per page. The includer defines
// the page source and the output.
layout(local_size_x = 256) in;

const uint _FLW_ORDER_GROUP = 256u;

// Visible bits of the model's page; firstObject: object uint of its instance 0.
uint _flw_orderedPage(uint page, out uint firstObject);

void _flw_orderedEmit(uint rank, uint objectUint);

shared uint _flw_orderRank[_FLW_ORDER_GROUP];

#ifdef _FLW_HAS_SUBGROUP
// Exclusive prefix of the workgroup's counts (<= 32 each), one ballot per count bit; *chunk: their sum.
uint _flw_orderedPrefix(uint count, out uint chunk) {
    uint prefix = 0u;
    uint subgroupTotal = 0u;
    for (uint bit = 0u; bit < 6u; bit++) {
        uvec4 ballot = subgroupBallot(((count >> bit) & 1u) != 0u);
        prefix += subgroupBallotExclusiveBitCount(ballot) << bit;
        subgroupTotal += subgroupBallotBitCount(ballot) << bit;
    }
    if (subgroupElect()) {
        _flw_orderRank[gl_SubgroupID] = subgroupTotal;
    }
    barrier();
    chunk = 0u;
    for (uint subgroup = 0u; subgroup < gl_NumSubgroups; subgroup++) {
        uint total = _flw_orderRank[subgroup];
        prefix += subgroup < gl_SubgroupID ? total : 0u;
        chunk += total;
    }
    return prefix;
}
#else
uint _flw_orderedPrefix(uint count, out uint chunk) {
    uint lane = gl_LocalInvocationID.x;
    _flw_orderRank[lane] = count;
    barrier();
    for (uint offset = 1u; offset < _FLW_ORDER_GROUP; offset <<= 1u) {
        uint add = lane >= offset ? _flw_orderRank[lane - offset] : 0u;
        barrier();
        _flw_orderRank[lane] += add;
        barrier();
    }
    chunk = _flw_orderRank[_FLW_ORDER_GROUP - 1u];
    return _flw_orderRank[lane] - count;
}
#endif

// The model's visible count; uniform control flow.
uint _flw_orderedCompact(uint pageCount, uint strideUints) {
    uint lane = gl_LocalInvocationID.x;
    uint total = 0u;
    for (uint first = 0u; first < pageCount; first += _FLW_ORDER_GROUP) {
        uint object = 0u;
        uint bits = first + lane < pageCount ? _flw_orderedPage(first + lane, object) : 0u;
        uint chunk;
        uint rank = total + _flw_orderedPrefix(uint(bitCount(bits)), chunk);
        total += chunk;
        for (; bits != 0u; bits &= bits - 1u) {
            _flw_orderedEmit(rank++, object + uint(findLSB(bits)) * strideUints);
        }
        barrier();
    }
    return total;
}
