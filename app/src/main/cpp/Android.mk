LOCAL_PATH:= $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := libtermux-bootstrap
LOCAL_SRC_FILES := termux-bootstrap-zip.S termux-bootstrap.c
include $(BUILD_SHARED_LIBRARY)

# usb-shim: interposes opendir() to hide /dev/bus/usb and /sys/bus/usb so that
# QEMU's libusb_init() succeeds inside an unprivileged Android app sandbox.
# Loaded into the qemu process via LD_PRELOAD.
include $(CLEAR_VARS)
LOCAL_MODULE := libtermux-usb-shim
LOCAL_SRC_FILES := usb-shim.c
LOCAL_LDLIBS := -ldl
include $(BUILD_SHARED_LIBRARY)

# rootless-console: a tiny executable that bridges a Termux session's pty to the guest's QEMU
# serial socket, so the VM console is a normal Termux session instead of a bespoke screen.
# ndk-build emits it with no extension (it needs a real entry point); app/build.gradle then stages
# it into jniLibs under a lib*.so name, because that is the only shape an APK will ship a native
# file in. RootlessConsole copies it into the app data dir and executes it from there.
include $(CLEAR_VARS)
LOCAL_MODULE := rootless-console
LOCAL_SRC_FILES := rootless-console.c
include $(BUILD_EXECUTABLE)
