LOCAL_PATH := $(call my-dir)

ifneq ($(strip $(DISCORD_SOCIAL_SDK_DIR)),)
include $(CLEAR_VARS)
LOCAL_MODULE := discord_partner_sdk
LOCAL_SRC_FILES := $(DISCORD_SOCIAL_SDK_DIR)/aar/jni/$(TARGET_ARCH_ABI)/libdiscord_partner_sdk.so
include $(PREBUILT_SHARED_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := discord-social-jni
LOCAL_SRC_FILES := discord-social-jni.cpp
LOCAL_C_INCLUDES := $(DISCORD_SOCIAL_SDK_DIR)/include
LOCAL_CPPFLAGS := -std=c++17
LOCAL_LDLIBS := -llog
LOCAL_SHARED_LIBRARIES := discord_partner_sdk
include $(BUILD_SHARED_LIBRARY)
endif
