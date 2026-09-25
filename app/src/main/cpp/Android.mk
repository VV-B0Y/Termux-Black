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
