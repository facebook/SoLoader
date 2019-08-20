load("//tools/build_defs/oss:soloader_defs.bzl", "android_aar")

android_aar(
    name = "soloader",
    manifest_skeleton = "AndroidManifestSkeleton.xml",
    deps = [
        "//java/com/facebook/soloader:soloader",
    ],
)

android_aar(
    name = "nativeloader",
    manifest_skeleton = "AndroidManifestSkeleton.xml",
    deps = [
        "//java/com/facebook/soloader/nativeloader:nativeloader",
    ],
)

android_aar(
    name = "soloaderdelegate",
    manifest_skeleton = "AndroidManifestSkeleton.xml",
    deps = [
        "//java/com/facebook/soloader/nativeloader/soloader:soloader",
    ],
)

android_aar(
    name = "annotation",
    manifest_skeleton = "AndroidManifestSkeleton.xml",
    deps = [
        "//java/com/facebook/soloader:annotation",
    ],
)

android_aar(
    name = "testloaderdelegate",
    manifest_skeleton = "AndroidManifestSkeleton.xml",
    deps = [
        "//java/com/facebook/soloader/nativeloader/testloader:testloader_aar",
    ],
)
