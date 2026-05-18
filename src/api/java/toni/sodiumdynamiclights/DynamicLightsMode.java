package toni.sodiumdynamiclights;

public enum DynamicLightsMode {
    OFF, SLOW, FAST, REALTIME;

    public boolean isEnabled() {
        return this != OFF;
    }
}
