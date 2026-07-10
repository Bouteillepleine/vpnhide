fn main() {
    println!("cargo:rerun-if-changed=c/config_wrapper.c");
    println!("cargo:rerun-if-changed=../../kmod/shared/vpnhide_logic.h");
    cc::Build::new()
        .file("c/config_wrapper.c")
        .include("../../kmod")
        .warnings(true)
        .extra_warnings(true)
        .compile("vpnhide_protocol_c_oracle");
}
