package dev.physicalist.nativecore;

/** JNI side of the Rust node-and-beam soft-body solver (softbody.rs), in the same library as the rigid-body backend. */
public final class NativeSoft {
    public static final boolean AVAILABLE;
    static {
        boolean ok = false;
        try { ok = NativeBridge.AVAILABLE && abi0() == 4; }
        catch (LinkageError error) { org.slf4j.LoggerFactory.getLogger("Physicalist").warn("Soft bodies unavailable: {}", error.toString()); }
        AVAILABLE = ok;
    }
    public static native int abi0();
    public static native long createWorld0();
    public static native void destroyWorld0(long world);
    /**
     * nodes {x,y,z,mass,shape group or -1,solid,friction or -1,wheel frame b,c or -1,axle in that frame}, beams
     * {a,b,k,c,deform,strength,kind,min,max}, aero {a,b,c,area scale,normal in the a-b-c frame,channel or -1,deflected
     * direction}, rotors {a,b,c,hub,axis,radius,blades,chord,pitch,inertia,power,max omega,count,7 hub nodes},
     * wheels {a,b,c,mount,up,forward (frame),radius,rest,travel,spring,damper,inertia,grip along,grip across,mass share,count,6 nodes},
     * params {radius, friction, skin drag, yield distance, shape stiffness, wheel rolling friction}.
     */
    public static native long add0(long world, double[] nodes, double[] beams, double[] aero, double[] rotors, double[] wheels, double[] params);
    /** Road wheel inputs: {steer radians, drive torque, brake torque, strut lift metres} per wheel. */
    public static native void wheels0(long world, long body, double[] input);
    public static native void remove0(long world, long body);
    public static native void terrain0(long world, long body, double[] boxes);
    public static native void step0(long world, double seconds, int substeps, double gravity, double airDensity, double[] wind);
    /** {x,y,z per node..., version, broken beams, sleeping, rotor count, per rotor {angle, omega, thrust, engine torque, induced, throttle, on}, wheel count, per wheel {angle, omega, compression, grounded, load, centre xyz}, diagnostics} */
    public static native double[] read0(long world, long body);
    public static native int[] components0(long world, long body);
    public static native void push0(long world, long body, int[] nodes, double[] velocityChange);
    public static native void velocity0(long world, long body, double[] velocity);
    /** Moves every node by an offset. */
    public static native void move0(long world, long body, double[] offset);
    /** Turns the body about a pivot and moves it: {pivot xyz, quaternion xyzw, offset xyz}. */
    public static native void transform0(long world, long body, double[] transform);
    public static native boolean latch0(long world, long body, int beam, boolean on);
    public static native boolean alive0(long world, long body, int beam);
    /** Whether each beam still exists. */
    public static native boolean[] beams0(long world, long body);
    /** Control surface angles per channel (radians), {throttle, on} per rotor, wheel rolling friction. */
    public static native void control0(long world, long body, double[] channels, double[] rotors, double wheelFriction);
    private NativeSoft() {}
}
