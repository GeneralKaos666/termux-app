LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)
# Drop unreferenced sections at link time; this is a linker flag, not a compile flag.
LOCAL_LDFLAGS := -Wl,--gc-sections
LOCAL_MODULE:= libtermux
LOCAL_SRC_FILES:= termux.c
include $(BUILD_SHARED_LIBRARY)
