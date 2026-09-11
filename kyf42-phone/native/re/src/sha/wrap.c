/**
 * @file wrap.c  SHA wrappers
 *
 * Copyright (C) 2022 Alfred E. Heggestad
 * Copyright (C) 2022 Sebastian Reimers <hallo@studio-link.de>
 */

#include <string.h>
#include <re_types.h>
#include <re_mbuf.h>
#ifdef USE_OPENSSL
#include <openssl/sha.h>
#elif defined (__APPLE__)
#include <CommonCrypto/CommonDigest.h>
#elif defined (WIN32)
#include <windows.h>
#include <wincrypt.h>
#elif defined (USE_MBEDTLS)
#include <mbedtls/sha1.h>
#include <mbedtls/sha256.h>
#include <mbedtls/error.h>
#endif
#include <re_sha.h>

#define DEBUG_MODULE "sha"
#define DEBUG_LEVEL 5
#include <re_dbg.h>

void sha1(const uint8_t *d, size_t n, uint8_t *md)
{
#ifdef USE_OPENSSL
	(void)SHA1(d, n, md);
#elif defined (__APPLE__)
	CC_SHA1(d, (uint32_t)n, md);
#elif defined (WIN32)
	HCRYPTPROV context;
	HCRYPTHASH hash;
	DWORD hash_size = SHA1_DIGEST_SIZE;
	CryptAcquireContext(&context, 0, 0, PROV_RSA_FULL, CRYPT_VERIFYCONTEXT);
	CryptCreateHash(context, CALG_SHA1, 0, 0, &hash);
	CryptHashData(hash, d, (DWORD)n, 0);
	CryptGetHashParam(hash, HP_HASHVAL, md, &hash_size, 0);
	CryptDestroyHash(hash);
	CryptReleaseContext(context, 0);
#elif defined (USE_MBEDTLS) || defined (MBEDTLS_MD_C)
	mbedtls_sha1_context ctx;
	mbedtls_sha1_init(&ctx);
	mbedtls_sha1_starts_ret(&ctx);
	mbedtls_sha1_update_ret(&ctx, d, n);
	mbedtls_sha1_finish_ret(&ctx, md);
	mbedtls_sha1_free(&ctx);
#else
	memset(md, 0, SHA1_DIGEST_SIZE);
#endif
}

void sha256(const uint8_t *d, size_t n, uint8_t *md)
{
#ifdef USE_OPENSSL
	(void)SHA256(d, n, md);
#elif defined (__APPLE__)
	CC_SHA256(d, (uint32_t)n, md);
#elif defined (WIN32)
	HCRYPTPROV context;
	HCRYPTHASH hash;
	DWORD hash_size = SHA256_DIGEST_SIZE;
	CryptAcquireContext(&context, 0, 0, PROV_RSA_FULL, CRYPT_VERIFYCONTEXT);
	CryptCreateHash(context, CALG_SHA_256, 0, 0, &hash);
	CryptHashData(hash, d, (DWORD)n, 0);
	CryptGetHashParam(hash, HP_HASHVAL, md, &hash_size, 0);
	CryptDestroyHash(hash);
	CryptReleaseContext(context, 0);
#elif defined (USE_MBEDTLS) || defined (MBEDTLS_MD_C)
	mbedtls_sha256_context ctx;
	mbedtls_sha256_init(&ctx);
	mbedtls_sha256_starts_ret(&ctx, 0);
	mbedtls_sha256_update_ret(&ctx, d, n);
	mbedtls_sha256_finish_ret(&ctx, md);
	mbedtls_sha256_free(&ctx);
#else
	memset(md, 0, SHA256_DIGEST_SIZE);
#endif
}

int sha256_printf(uint8_t md[32], const char *fmt, ...)
{
	struct mbuf mb;
	va_list ap;
	int err;
	mbuf_init(&mb);
	va_start(ap, fmt);
	err = mbuf_vprintf(&mb, fmt, ap);
	va_end(ap);
	if (!err)
		sha256(mb.buf, mb.end, md);
	mbuf_reset(&mb);
	return err;
}
