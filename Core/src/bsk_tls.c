#include <dirent.h>
#include <pthread.h>
#include <string.h>
#include <openssl/err.h>
#include <openssl/sha.h>
#include <openssl/x509.h>
#include <openssl/x509_vfy.h>
#include <openssl/x509v3.h>
#include "bsk_internal.h"


static bool spki_sha256_base64(const uint8_t *der, size_t der_len, char *out,
			       size_t out_len)
{
	const unsigned char *p = der;
	X509 *cert;
	unsigned char *spki = NULL;
	int spki_len;
	uint8_t digest[SHA256_DIGEST_LENGTH];
	size_t olen = out_len;
	int err;

	cert = d2i_X509(NULL, &p, (long)der_len);
	if (!cert)
		return false;

	spki_len = i2d_X509_PUBKEY(X509_get_X509_PUBKEY(cert), &spki);
	X509_free(cert);
	if (spki_len <= 0 || !spki)
		return false;

	SHA256(spki, (size_t)spki_len, digest);
	OPENSSL_free(spki);

	err = base64_encode(digest, sizeof(digest), out, &olen);
	if (err || olen >= out_len)
		return false;
	out[olen] = '\0';

	return true;
}


static void tls_warn(bsk_engine *e, const char *msg)
{
	bsk_log_emit(e, BSK_LOG_WARNING, BSK_LOG_DOMAIN_CORE, msg, strlen(msg));
}


static void pin_reset(bsk_engine *e)
{
	e->pin_matched = false;
	e->pin_depth_seen = false;
}


static void pin_check(bsk_engine *e, const uint8_t *der, size_t der_len)
{
	char b64[64];
	size_t i;

	if (!der || !spki_sha256_base64(der, der_len, b64, sizeof(b64)))
		return;

	for (i = 0; i < e->pin_count; i++) {
		if (0 == strcmp(e->pins[i], b64))
			e->pin_matched = true;
	}
}


static int verify_handler(int preverify_ok, int depth, const uint8_t *der,
			  size_t der_len, void *arg)
{
	bsk_engine *e = arg;
	bool matched;

	if (!e)
		return preverify_ok;

	if (!e->pin_depth_seen || depth >= e->pin_last_depth) {
		e->pin_matched = false;
		e->pin_depth_seen = true;
	}
	e->pin_last_depth = depth;

	pin_check(e, der, der_len);

	if (!preverify_ok) {
		pin_reset(e);
		tls_warn(e, "bsk: tls: certificate chain verification failed");
		return 0;
	}

	if (depth > 0)
		return 1;

	matched = e->pin_matched;
	pin_reset(e);

	if (!matched) {
		tls_warn(e, "bsk: tls: no certificate matched a pinned key");
		return 0;
	}

	return 1;
}


#if !defined(__APPLE__)
static pthread_mutex_t store_lock = PTHREAD_MUTEX_INITIALIZER;
static X509_STORE *cached_store;
static const char *const *cached_dirs;
static size_t cached_dir_count;


static bool set_expected_host(X509_STORE_CTX *ctx, const char *host)
{
	X509_VERIFY_PARAM *param = X509_STORE_CTX_get0_param(ctx);
	struct sa sa;

	if (0 == sa_set_str(&sa, host, 0))
		return 1 == X509_VERIFY_PARAM_set1_ip_asc(param, host);

	X509_VERIFY_PARAM_set_hostflags(param,
					X509_CHECK_FLAG_NO_PARTIAL_WILDCARDS);
	return 1 == X509_VERIFY_PARAM_set1_host(param, host, 0);
}


static void visit_chain(X509_STORE_CTX *ctx, bsk_trust_cert_h *certh, void *arg)
{
	STACK_OF(X509) *verified = X509_STORE_CTX_get0_chain(ctx);
	int i;

	for (i = 0; verified && i < sk_X509_num(verified); i++) {
		unsigned char *der = NULL;
		int len = i2d_X509(sk_X509_value(verified, i), &der);

		if (len > 0 && der)
			certh(der, (size_t)len, arg);
		OPENSSL_free(der);
	}
}


static size_t load_ca_dir(X509_STORE *store, const char *dir)
{
	DIR *d = opendir(dir);
	struct dirent *ent;
	size_t loaded = 0;

	if (!d)
		return 0;

	while ((ent = readdir(d))) {
		char path[512];

		if (ent->d_name[0] == '.')
			continue;
		if (re_snprintf(path, sizeof(path), "%s/%s", dir, ent->d_name) <
		    0)
			continue;
		if (1 == X509_STORE_load_file(store, path))
			++loaded;
	}

	closedir(d);
	ERR_clear_error();

	return loaded;
}


static void load_ca_dirs(X509_STORE *store, const char *const *dirs,
			 size_t dir_count)
{
	size_t i;

	for (i = 0; i < dir_count; i++) {
		if (load_ca_dir(store, dirs[i]))
			return;
	}
}


static X509_STORE *system_store(const char *const *dirs, size_t dir_count)
{
	X509_STORE *store = NULL;

	pthread_mutex_lock(&store_lock);
	if (cached_store &&
	    (cached_dirs != dirs || cached_dir_count != dir_count)) {
		X509_STORE_free(cached_store);
		cached_store = NULL;
	}
	if (!cached_store) {
		X509_STORE *fresh = X509_STORE_new();

		if (fresh && 1 == X509_STORE_set_default_paths(fresh)) {
			load_ca_dirs(fresh, dirs, dir_count);
			cached_store = fresh;
			cached_dirs = dirs;
			cached_dir_count = dir_count;
		} else {
			X509_STORE_free(fresh);
		}
	}
	if (cached_store && 1 == X509_STORE_up_ref(cached_store))
		store = cached_store;
	pthread_mutex_unlock(&store_lock);

	return store;
}


bool bsk_trust_verify(const char *const *ca_dirs, size_t dir_count,
		      const char *host, const uint8_t *const *derv,
		      const size_t *lenv, size_t count, bsk_trust_cert_h *certh,
		      void *arg)
{
	X509_STORE *store = system_store(ca_dirs, dir_count);
	X509_STORE_CTX *ctx = X509_STORE_CTX_new();
	STACK_OF(X509) *chain = sk_X509_new_null();
	bool ok = false;
	size_t i;

	if (!str_isset(host) || !count || !store || !ctx || !chain)
		goto out;

	for (i = 0; i < count; i++) {
		const unsigned char *p = derv[i];
		X509 *cert = d2i_X509(NULL, &p, (long)lenv[i]);

		if (!cert || !sk_X509_push(chain, cert)) {
			X509_free(cert);
			goto out;
		}
	}

	if (1 != X509_STORE_CTX_init(ctx, store, sk_X509_value(chain, 0),
				     chain) ||
	    1 != X509_STORE_CTX_set_default(ctx, "ssl_server") ||
	    !set_expected_host(ctx, host))
		goto out;

	ok = 1 == X509_verify_cert(ctx);
	if (ok && certh)
		visit_chain(ctx, certh, arg);

out:
	X509_STORE_CTX_free(ctx);
	X509_STORE_free(store);
	sk_X509_pop_free(chain, X509_free);
	ERR_clear_error();

	return ok;
}


bool bsk_trust_system(const char *host, const uint8_t *const *derv,
		      const size_t *lenv, size_t count, bsk_trust_cert_h *certh,
		      void *arg)
{
#if defined(__ANDROID__)
	static const char *const dirs[] = {
		"/apex/com.android.conscrypt/cacerts",
		"/system/etc/security/cacerts",
	};

	return bsk_trust_verify(dirs, RE_ARRAY_SIZE(dirs), host, derv, lenv,
				count, certh, arg);
#else
	return bsk_trust_verify(NULL, 0, host, derv, lenv, count, certh, arg);
#endif
}
#endif


static void pin_visit(const uint8_t *der, size_t len, void *arg)
{
	pin_check(arg, der, len);
}


static bool trust_handler(const char *host, const uint8_t *const *derv,
			  const size_t *lenv, size_t count, void *arg)
{
	bsk_engine *e = arg;

	pin_reset(e);
	if (!bsk_trust_system(host, derv, lenv, count,
			      e->pin_count ? pin_visit : NULL, e)) {
		pin_reset(e);
		tls_warn(e, "bsk: tls: the server certificate is not trusted");
		return false;
	}

	if (!e->pin_count)
		return true;

	if (!e->pin_matched) {
		tls_warn(e, "bsk: tls: no certificate matched a pinned key");
		return false;
	}

	pin_reset(e);
	return true;
}


int bsk_tls_install(bsk_engine *e, struct tls *tls)
{
	int err = 0;

	if (e->pin_count)
		err = tls_set_verify_server_handler(tls, verify_handler, e);
	if (!err && e->verify_server)
		err = tls_set_trust_handler(tls, trust_handler, e);

	return err;
}
