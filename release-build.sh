#!/bin/env fish
git clean -fdx
chmod +x gradlew
cp -r ../local.properties .
./gradlew clean
./gradlew :app:assembleRelease
