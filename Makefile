.PHONY: help icons debug-apk clean all

LOGO_SOURCE ?= branding/logo.png
ICON_SCRIPT := scripts/generate_launcher_icons.py
DEBUG_APK := app/build/outputs/apk/debug/app-debug.apk

help:
	@printf '%s\n' \
	  'Available targets:' \
	  '  make icons      Regenerate Android launcher icons from $(LOGO_SOURCE)' \
	  '  make debug-apk  Build the debug APK with Gradle' \
	  '  make clean      Clean Gradle build outputs' \
	  '  make all        Regenerate icons and build the debug APK'

icons:
	python $(ICON_SCRIPT) --source $(LOGO_SOURCE)

debug-apk:
	./gradlew assembleDebug

clean:
	./gradlew clean

all: icons debug-apk
