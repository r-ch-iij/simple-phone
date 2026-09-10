cmake_minimum_required(VERSION 3.22.1)
project("native-sip" C)

set(NATIVE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../native)

# mbedcrypto のみ (HMAC/SHA/MD5 - SIP認証用)
# libmbedtls.a (TLS) と libmbedx509.a (X.509) は不要
add_library(mbedcrypto STATIC IMPORTED)
set_target_properties(mbedcrypto PROPERTIES
    IMPORTED_LOCATION ${NATIVE_DIR}/build_mbedtls/library/libmbedcrypto.a
    INTERFACE_INCLUDE_DIRECTORIES ${NATIVE_DIR}/mbedtls/include
)

add_library(re STATIC IMPORTED)
set_target_properties(re PROPERTIES
    IMPORTED_LOCATION ${NATIVE_DIR}/build_re/libre.a
    INTERFACE_INCLUDE_DIRECTORIES ${NATIVE_DIR}/re/include
)

add_library(rem STATIC IMPORTED)
set_target_properties(rem PROPERTIES
    IMPORTED_LOCATION ${NATIVE_DIR}/build_rem/librem.a
    INTERFACE_INCLUDE_DIRECTORIES ${NATIVE_DIR}/rem/include
)

add_library(baresip STATIC IMPORTED)
set_target_properties(baresip PROPERTIES
    IMPORTED_LOCATION ${NATIVE_DIR}/build_baresip_final/libbaresip.a
    INTERFACE_INCLUDE_DIRECTORIES "${NATIVE_DIR}/baresip/include;${NATIVE_DIR}/baresip/src"
)

add_library(opus STATIC IMPORTED)
set_target_properties(opus PROPERTIES
    IMPORTED_LOCATION ${NATIVE_DIR}/build_opus/libopus.a
    INTERFACE_INCLUDE_DIRECTORIES ${NATIVE_DIR}/opus-1.4/include
)

add_library(spandsp STATIC IMPORTED)
set_target_properties(spandsp PROPERTIES
    IMPORTED_LOCATION ${NATIVE_DIR}/build_spandsp_g722/libspandsp.a
    INTERFACE_INCLUDE_DIRECTORIES ${NATIVE_DIR}/spandsp/include
)

# JNI ブリッジ
add_library(native-sip SHARED
    native-sip-jni.c
)

target_include_directories(native-sip PRIVATE
    ${NATIVE_DIR}/re/include
    ${NATIVE_DIR}/rem/include
    ${NATIVE_DIR}/mbedtls/include
    ${NATIVE_DIR}/baresip/include
    ${NATIVE_DIR}/baresip/src
    ${NATIVE_DIR}/opus-1.4/include
    ${NATIVE_DIR}/spandsp/include
)

target_compile_definitions(native-sip PRIVATE
    USE_MBEDTLS
    MBEDTLS_MD_C
    ARRAY_SIZE=RE_ARRAY_SIZE
)

target_link_libraries(native-sip
    baresip
    rem
    re
    mbedcrypto
    opus
    spandsp
    OpenSLES
    android
    log
)
