package rodozodo.infinite.client.config;

// Modes controlling how distant Lod terrain transitions into the sky at the horizon

public enum HorizonFog {
    FOG_AND_EDGE(true, true),
    FOG_ONLY(true, false),
    EDGE_ONLY(false, true),
    NONE(false, false);

    public final boolean lodFog;
    public final boolean edgeFade;

    HorizonFog(boolean lodFog, boolean edgeFade) {
        this.lodFog = lodFog;
        this.edgeFade = edgeFade;
    }

    public boolean clearsVanillaFog() {
        return !this.lodFog;
    }
}
