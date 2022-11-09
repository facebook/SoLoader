# SoLoader

[![Support Ukraine](https://img.shields.io/badge/Support-Ukraine-FFD500?style=flat&labelColor=005BBB)](https://opensource.fb.com/support-ukraine)

SoLoader is a native code loader for Android. It takes care of unpacking your native libraries
and recursively loads dependencies on Android API 23 and earlier, since those old OS versions
do not support all of that functionality out of box.

## Requirements
SoLoader is useful for applications running on Android API 23 and earlier. SoLoader should not
be used on Android API 24 and above unless the app is delivered as
[Exopackage](https://buck.build/article/exopackage.html), requires
[NativeRelinker](https://buck.build/javadoc/com/facebook/buck/android/relinker/NativeRelinker.html)
or uses [Superpack](https://engineering.fb.com/2021/09/13/core-data/superpack/)
compression.

## Including SoLoader in your apps
You can use [prebuilt aars](https://github.com/facebook/soloader/releases/latest)
or fetch SoLoader from Maven repository by adding the following to your
`build.gradle` file:
```groovy
implementation 'com.facebook.soloader:soloader:0.10.4+'
```

## Building from source
To build SoLoader from source you'll need [Buck](https://buckbuild.com/).
Once you have Buck installed execute following commands from the project root
directory:
```shell
  buck fetch //...
  buck build :soloader
```
The build command generates `buck-out/gen/soloader.aar` file.

## Join our community
Please use our [issues page](https://github.com/facebook/soloader/issues) to let us know of any problems.
See the [CONTRIBUTING](https://github.com/facebook/soloader/blob/main/CONTRIBUTING.md) file for how to
help out.

## License
SoLoader is [Apache-2.0-licensed](https://github.com/facebook/soloader/blob/main/LICENSE).
