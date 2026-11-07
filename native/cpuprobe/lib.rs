use std::ffi::c_void;

/// JNI: `dev.physicalist.nativecore.NativeCpu.level0()`. Returns 3 when every x86-64-v3 feature is
/// present and enabled by the OS (AVX state via XGETBV), otherwise 1.
#[no_mangle]
pub extern "system" fn Java_dev_physicalist_nativecore_NativeCpu_level0(_env: *mut c_void, _class: *mut c_void) -> i32 {
    #[cfg(target_arch = "x86_64")]
    {
        if is_x86_feature_detected!("avx")
            && is_x86_feature_detected!("avx2")
            && is_x86_feature_detected!("bmi1")
            && is_x86_feature_detected!("bmi2")
            && is_x86_feature_detected!("f16c")
            && is_x86_feature_detected!("fma")
            && is_x86_feature_detected!("lzcnt")
            && is_x86_feature_detected!("movbe")
            && is_x86_feature_detected!("xsave")
        {
            return 3;
        }
    }
    1
}
