FROM eclipse-temurin:21-jdk-jammy

ENV DEBIAN_FRONTEND=noninteractive

# Android SDK の設定
ENV ANDROID_HOME=/opt/android-sdk
ENV ANDROID_SDK_ROOT=${ANDROID_HOME}
ENV PATH="${PATH}:${ANDROID_HOME}/cmdline-tools/latest/bin:${ANDROID_HOME}/platform-tools"

# 基本ツール
RUN apt-get update && apt-get install -y --no-install-recommends \
    wget unzip && \
    rm -rf /var/lib/apt/lists/*

# Android SDK Command-line Tools
RUN mkdir -p ${ANDROID_HOME}/cmdline-tools && \
    cd /tmp && \
    wget -q https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip && \
    unzip -q commandlinetools-linux-11076708_latest.zip && \
    mv cmdline-tools ${ANDROID_HOME}/cmdline-tools/latest && \
    rm commandlinetools-linux-11076708_latest.zip

# Android SDK パッケージのインストール
RUN yes | sdkmanager --licenses > /dev/null 2>&1 && \
    sdkmanager --install \
    "platforms;android-34" \
    "build-tools;34.0.0" \
    "platform-tools" \
    "ndk;27.0.12077973" \
    "cmake;3.22.1"

# Gradle 8.13
RUN cd /tmp && \
    wget -q https://services.gradle.org/distributions/gradle-8.13-bin.zip && \
    unzip -q gradle-8.13-bin.zip -d /opt && \
    ln -s /opt/gradle-8.13/bin/gradle /usr/local/bin/gradle && \
    rm gradle-8.13-bin.zip

WORKDIR /src
