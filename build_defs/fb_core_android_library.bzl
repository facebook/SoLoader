"""OSS shim of the internal fb_core_android_library macros."""

def fb_core_android_library(**kwargs):
    native.android_library(**kwargs)
