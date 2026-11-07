package dev.physicalist.nativecore;
/**
 * Asks the dependency-free physicalist_cpu library whether this processor runs the x86-64-v3
 * (AVX2/FMA/BMI) build of the native backend. That build computes bit-identical results ~1.5x faster.
 * The probe lives in its own library so the JVM never sees two copies of the NativeBridge symbols.
 */
final class NativeCpu {
 static native int level0();
 private NativeCpu(){}
}
