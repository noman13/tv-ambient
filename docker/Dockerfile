# Build environment for TV Ambient 2.0: JDK 17, Android SDK 34, NDK r26d (LTS), Rust (pinned) with the Android
# targets, and cargo-ndk. The host needs nothing but Docker; scripts/build.sh builds this image on first use.
#   docker build -t tv-ambient-builder docker/
FROM eclipse-temurin:17-jdk

ARG CMDLINE_TOOLS_VERSION=11076708
ARG COMPILE_SDK=34
ARG BUILD_TOOLS=34.0.0
ARG NDK_VERSION=26.3.11579264
ARG RUST_VERSION=1.83.0
ARG CARGO_NDK_VERSION=3.5.4

ENV ANDROID_HOME=/opt/android-sdk \
    ANDROID_SDK_ROOT=/opt/android-sdk \
    ANDROID_NDK_HOME=/opt/android-sdk/ndk/${NDK_VERSION} \
    GRADLE_USER_HOME=/gradle \
    RUSTUP_HOME=/opt/rustup \
    CARGO_HOME=/opt/cargo \
    DEBIAN_FRONTEND=noninteractive

# gcc: the linker for host builds (cargo install, `cargo test` of the core crate on the host).
RUN apt-get update \
    && apt-get install -y --no-install-recommends unzip curl ca-certificates gcc libc6-dev \
    && rm -rf /var/lib/apt/lists/*

# sdkmanager expects to live in cmdline-tools/latest.
RUN mkdir -p "$ANDROID_HOME/cmdline-tools" \
    && curl -fsSL -o /tmp/cmdline-tools.zip \
       "https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS_VERSION}_latest.zip" \
    && unzip -q /tmp/cmdline-tools.zip -d "$ANDROID_HOME/cmdline-tools" \
    && mv "$ANDROID_HOME/cmdline-tools/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest" \
    && rm /tmp/cmdline-tools.zip

ENV PATH="/opt/cargo/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"

RUN yes | sdkmanager --licenses > /dev/null \
    && sdkmanager --install "platform-tools" "platforms;android-${COMPILE_SDK}" "build-tools;${BUILD_TOOLS}" \
       "ndk;${NDK_VERSION}"

# Rust, pinned, with the two device ABIs and x86_64 for emulator debug builds.
RUN curl -fsSL https://sh.rustup.rs -o /tmp/rustup-init.sh \
    && sh /tmp/rustup-init.sh -y --profile minimal --default-toolchain "${RUST_VERSION}" --no-modify-path \
    && rm /tmp/rustup-init.sh \
    && rustup target add armv7-linux-androideabi aarch64-linux-android x86_64-linux-android \
    && cargo install cargo-ndk --version "${CARGO_NDK_VERSION}" --locked \
    && rm -rf /opt/cargo/registry /opt/cargo/git

# Caches live in named volumes at run time (/gradle, /cargo); world-writable so the container can run as any uid.
RUN mkdir -p /gradle /cargo && chmod 777 /gradle /cargo && chmod -R a+rwX /opt/rustup /opt/cargo

WORKDIR /src
