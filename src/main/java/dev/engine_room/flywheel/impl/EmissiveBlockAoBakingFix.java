package dev.engine_room.flywheel.impl;

public enum EmissiveBlockAoBakingFix {
    ON {
        @Override
        public boolean shouldApplyAo(int lightValue) {
            return lightValue <= 1;
        }
    },
    OFF {
        @Override
        public boolean shouldApplyAo(int lightValue) {
            return lightValue == 0;
        }
    };

    public abstract boolean shouldApplyAo(int lightValue);
}
