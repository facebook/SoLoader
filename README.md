# SoLoader [![Build Status](https://travis-ci.com/facebook/SoLoader.svg?branch=master)](https://travis-ci.com/facebook/SoLoader)
SoLoader is a native code loader for Android. It takes care of unpacking your native libraries
and recursively loads dependencies on platforms that don't support that out of the box.

## Requirements
SoLoader can be included in any Android application.

## Including SoLoader in your apps
You can use [prebuilt aars](https://github.com/facebook/soloader/releases/latest)
or fetch SoLoader from Maven repository by adding the following to your
`build.gradle` file:
```groovy
implementation 'com.facebook.soloader:soloader:0.9.0+'
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
See the [CONTRIBUTING](https://github.com/facebook/soloader/blob/master/CONTRIBUTING.md) file for how to
help out.

## License
SoLoader is [Apache-2.0-licensed](https://github.com/facebook/soloader/blob/master/LICENSE).
