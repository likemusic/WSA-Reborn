#!/bin/bash
# TalkBack from open source (github.com/google/talkback) with the applicationId of the Microsoft stock build - 2026-10-06
export JAVA_HOME=/aosp-16/prebuilts/jdk/jdk17/linux-x86 ANDROID_HOME=/root/android-sdk ANDROID_SDK=/root/android-sdk
export PATH=$JAVA_HOME/bin:/root/tb-tools/gradle-8.14.3/bin:$PATH GRADLE_USER_HOME=/root/.gradle
cd /root/talkback-src
echo "sdk.dir=/root/android-sdk" > local.properties
gradle --no-daemon -Dorg.gradle.jvmargs=-Xmx6g assemblePhoneRelease
echo "BUILD_EXIT_CODE=$?"
