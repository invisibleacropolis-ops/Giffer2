# Android SDK Setup and Test Execution Notes

This document captures the exact steps used to provision the Android SDK in the containerized workspace and to execute the project's unit test suite. It is intended to help other engineers reproduce the setup quickly and to highlight any noteworthy observations from the run.

## Environment

- **Host OS:** Debian-based container image supplied by the execution environment
- **Java runtime:** OpenJDK 21.0.2 (matches the version required by Android Gradle Plugin 8.13.0)
- **Android SDK location:** `$HOME/android-sdk`

## SDK Installation Steps

1. **Create the SDK directory and download the command-line tools.**
   ```bash
   mkdir -p "$HOME/android-sdk/cmdline-tools"
   cd "$HOME/android-sdk"
   wget https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
   unzip commandlinetools-linux-11076708_latest.zip -d cmdline-tools
   mv cmdline-tools/cmdline-tools cmdline-tools/latest
   ```

2. **Export the required environment variables (add them to your shell profile for reuse).**
   ```bash
   export ANDROID_HOME="$HOME/android-sdk"
   export ANDROID_SDK_ROOT="$ANDROID_HOME"
   export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"
   ```

3. **Install the required SDK packages.**
   ```bash
   yes | sdkmanager "platform-tools" \
                    "platforms;android-36" \
                    "build-tools;36.0.0"
   ```
   Gradle will automatically provision any additional build-tools revisions declared by the project (for example, build-tools 35.0.0) when you run the build for the first time. Accept the licenses when prompted.

## Test Execution

Run the JVM unit tests from the project root once the SDK is configured:

```bash
./gradlew test --console=plain
```

### Result Summary

- Status: **PASS**
- Build duration: ~1 minute 20 seconds on the reference container
- Notable output: Gradle downloaded Android SDK Build-Tools 35.0.0 on-demand to satisfy the build configuration.

The successful completion of the task confirms that the Android SDK installation is functional and that the unit tests pass with the current source code.

## Troubleshooting Tips

- If the `sdkmanager` command is not found, ensure the `cmdline-tools/latest/bin` directory is at the front of your `PATH`.
- When running inside a clean container, accepting the SDK licenses requires interactive input. Prepending `yes |` to the `sdkmanager` command handles this automatically.
- Gradle may request older build-tools versions. Allow it to download the additional packages or install them explicitly with `sdkmanager` if network restrictions apply.
- Should tests fail due to missing emulator/device dependencies, verify that only unit tests (not instrumentation tests) are being executed. The `testDebugUnitTest` task runs on the JVM and does not require an Android device.
