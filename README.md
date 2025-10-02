# Giffer2 Android Project

This repository contains the Giffer2 Android application. The project targets Android API level 36 and relies on the Android SDK command-line tools for local development and CI builds.

## Android SDK setup

The Android command-line tools are not bundled with the repository. Install them manually and point Gradle to the SDK location before building.

```bash
# Download and unpack the command line tools
mkdir -p "$HOME/android-sdk"
cd "$HOME/android-sdk"
wget https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
unzip commandlinetools-linux-11076708_latest.zip -d cmdline-tools-temp
mkdir -p cmdline-tools/latest
mv cmdline-tools-temp/cmdline-tools/* cmdline-tools/latest/
rm -rf cmdline-tools-temp commandlinetools-linux-11076708_latest.zip

# Configure environment variables for the current shell session
export ANDROID_HOME="$HOME/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"

# Install the required SDK components
yes | sdkmanager "platform-tools" \
                 "platforms;android-36" \
                 "build-tools;36.0.0"
```

> **Note:** Accept the Android SDK licenses when prompted. You may want to persist the environment variables above in your shell profile for repeated use.

## Building and testing

Once the SDK is installed and the environment variables are exported, you can build and run the unit tests from the project root:

```bash
./gradlew assembleDebug --console=plain
./gradlew testDebugUnitTest --console=plain
```

Both commands should complete successfully, producing a debug APK under `app/build/outputs/apk/debug/` and executing the unit test suite.


## Additional resources

- [Android SDK setup and unit test execution notes](docs/android-sdk-setup-and-testing.md) – step-by-step commands and troubleshooting tips captured during the latest validation run.
