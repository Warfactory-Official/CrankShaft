if (flw_oitActive) {
        uvec2 flw_coord = _FLW_SORT_PIXEL;
        uint flw_pixel = flw_coord.y * uint(screenSize.x) + flw_coord.x;
        uint flw_node = flw_heads[flw_pixel];
        uint flw_order[64];
        int flw_length = 0;
        while (flw_node != 0xffffffffu && flw_length < 64) {
            uint flw_next = flw_nodes[flw_node * 2u].x;
            int flw_at = flw_length;
            while (flw_at > 0) {
                uint flw_other = flw_order[flw_at - 1];
                if (flw_layerBefore(flw_other, flw_node)) break;
                flw_order[flw_at] = flw_other;
                --flw_at;
            }
            flw_order[flw_at] = flw_node;
            ++flw_length;
            {
                _FLW_VISIBILITY_BODY
                flw_nodes[flw_node * 2u + 1u].w = floatBitsToUint(transparentDensity);
            }
            flw_node = flw_next;
        }
        if (flw_node != 0xffffffffu) atomicOr(flw_overflow, 1u);
        if (flw_length > 0) {
            flw_heads[flw_pixel] = flw_order[0];
            for (int flw_i = 0; flw_i < flw_length; ++flw_i)
                flw_nodes[flw_order[flw_i] * 2u].x = flw_i + 1 < flw_length ? flw_order[flw_i + 1] : 0xffffffffu;
            atomicMax(flw_maxLayers, uint(flw_length));
            uint flw_tile = (flw_coord.y >> 4u) * ((uint(screenSize.x) + 15u) >> 4u) + (flw_coord.x >> 4u);
            if (flw_tiles[flw_tile] < uint(flw_length)) atomicMax(flw_tiles[flw_tile], uint(flw_length));
        }
    }
