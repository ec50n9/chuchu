.PHONY: build app test fmt lint

build:
	cd zig-src && zig build -Doptimize=small -Dandroid-ndk="$${ANDROID_NDK_HOME:-$$ANDROID_NDK_ROOT}" jni

app:
	cd android && ./gradlew installDebug
	adb shell am start -n io.github.ec50n9.roost/.MainActivity

test:
	cd android && ./gradlew testDebugUnitTest

fmt:
	ktfmt --kotlinlang-style $(shell find android/app/src -name '*.kt')

lint:
	ktlint --editorconfig=android/.editorconfig "android/app/src/**/*.kt" "android/**/*.gradle.kts"
