use std::env;

fn main() {
    let target = env::var("TARGET").unwrap_or_default();
    if target != "aarch64-linux-android" {
        return;
    }
    let manifest = env::var("CARGO_MANIFEST_DIR").unwrap();

    // Android folds pthread/rt into libc, but unicorn's bundled QEMU still asks the
    // linker for -lpthread/-lrt. Empty stub archives satisfy that.
    println!("cargo:rustc-link-search=native={manifest}/stublibs");

    // Unicorn's JIT (TCG) references __clear_cache to flush the icache after codegen;
    // it lives in compiler-rt builtins, which our link line must include explicitly.
    if let Ok(ndk) = env::var("ANDROID_NDK_HOME") {
        // darwin host toolchain; clang 17 ships with NDK r26.
        for ver in ["17", "17.0.2"] {
            let dir = format!("{ndk}/toolchains/llvm/prebuilt/darwin-x86_64/lib/clang/{ver}/lib/linux");
            if std::path::Path::new(&dir).exists() {
                println!("cargo:rustc-link-search=native={dir}");
                break;
            }
        }
        // Whole-archive not needed; the linker pulls __clear_cache on demand as long as
        // this comes after libunicorn on the link line (root build.rs libs come last).
        println!("cargo:rustc-link-lib=static=clang_rt.builtins-aarch64-android");
    }
}
