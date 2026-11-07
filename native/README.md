# Physicalist native backend

Independent JNI ABI 2 backed by upstream `rapier3d-f64` 0.36.1. Source and
dependency versions are recorded in `Cargo.toml` and `Cargo.lock`. No MCS binary
or source is needed to build it.

Requires Rust 1.99.1 or a compatible newer stable toolchain. On Windows install
the GNU Rust target and an x86-64 MinGW toolchain, or use the MSVC target with
Visual Studio C++ Build Tools. The supplied Windows DLL was built with GNU and
static libgcc; it depends only on Windows system DLLs.

From an ASCII checkout path:

```powershell
cd native
cargo test --locked
$env:RUSTFLAGS='-C link-arg=-static-libgcc' # GNU Windows only
cargo build --release --locked
Copy-Item target/release/physicalist_native.dll ../src/main/resources/natives/windows-x86_64/
cd ..
./gradlew.bat build
```

GNU Windows linkers may reject non-ASCII project paths. In that case copy this
`native` directory to an ASCII build path (for example `C:/native/physicalist`),
build there and copy the resulting binary back. Keep `Cargo.lock` unchanged.
Do not put an unrelated DLL in the resource directory. The Java loader hashes
the bundled file before extracting it and checks the JNI ABI after loading.

On Linux, build normally and copy `libphysicalist_native.so` to
`src/main/resources/natives/linux-x86_64/`; use `linux-aarch64` on ARM64.
On macOS use `libphysicalist_native.dylib` in `macos-x86_64` or `macos-aarch64`.
The current locally supplied release binary is Windows x86-64. A source build
for another platform must package its own matching binary; otherwise Java
physics is used and the reason is logged.

Run `gradlew runGameTestServer` after packaging the binary. This tests the JNI
loader, cached terrain, compound bodies, ragdolls and the Minecraft adapter, not
just the isolated Rust world. Always inspect the required-test results.

The JNI interface is a low-level, versioned implementation detail. Never pass
untrusted arbitrary native IDs or packed arrays from network packets. Normal
integrations use `NativeWorlds.enqueue(PhysicsBody)` on the server thread.
Third-party dependency notices are packaged under `META-INF/licenses/native`.
