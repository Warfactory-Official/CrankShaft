if (flw_oitActive) {
        uvec2 flw_coord = _FLW_SORT_PIXEL;
        uint flw_pixel = flw_coord.y * uint(screenSize.x) + flw_coord.x;
        // helper lanes past an odd framebuffer edge own no list; an out-of-range head reads as node 0
        uint flw_node = all(lessThan(flw_coord, uvec2(screenSize))) ? flw_heads[flw_pixel] : 0xffffffffu;
        uint flw_length = 0u;
        while (flw_node != 0xffffffffu) {
            uint flw_next = flw_nodes[flw_node * 2u].x;
            ++flw_length;
            {
                _FLW_VISIBILITY_BODY
                flw_nodes[flw_node * 2u + 1u].w = floatBitsToUint(transparentDensity);
            }
            flw_node = flw_next;
        }
        if (flw_length > 0u) {
            uint flw_head = flw_heads[flw_pixel];
            for (uint flw_run = 1u; flw_run < flw_length; flw_run *= 2u) {
                uint flw_left = flw_head;
                uint flw_tail = 0xffffffffu;
                flw_head = 0xffffffffu;
                while (flw_left != 0xffffffffu) {
                    uint flw_right = flw_left;
                    uint flw_leftCount = 0u;
                    for (uint i = 0u; i < flw_run && flw_right != 0xffffffffu; ++i) {
                        ++flw_leftCount;
                        flw_right = flw_nodes[flw_right * 2u].x;
                    }
                    uint flw_rightCount = flw_run;
                    while (flw_leftCount > 0u || (flw_rightCount > 0u && flw_right != 0xffffffffu)) {
                        uint flw_selected;
                        if (flw_leftCount > 0u && (flw_rightCount == 0u || flw_right == 0xffffffffu
                                || flw_layerBefore(flw_left, flw_right))) {
                            flw_selected = flw_left;
                            flw_left = flw_nodes[flw_left * 2u].x;
                            --flw_leftCount;
                        } else {
                            flw_selected = flw_right;
                            flw_right = flw_nodes[flw_right * 2u].x;
                            --flw_rightCount;
                        }
                        if (flw_tail == 0xffffffffu) flw_head = flw_selected;
                        else flw_nodes[flw_tail * 2u].x = flw_selected;
                        flw_tail = flw_selected;
                    }
                    flw_left = flw_right;
                }
                flw_nodes[flw_tail * 2u].x = 0xffffffffu;
            }
            flw_heads[flw_pixel] = flw_head;
            if (_FLW_SORT_COUNTS_LAYERS && flw_maxLayers < flw_length + _FLW_SORT_LAYER_BIAS)
                atomicMax(flw_maxLayers, flw_length + _FLW_SORT_LAYER_BIAS);
        }
    }
