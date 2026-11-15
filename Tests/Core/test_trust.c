#include "test_common.h"

enum { MAX_CERTS = 8 };

typedef void(bsk_trust_cert_h)(const uint8_t *der, size_t len, void *arg);
bool bsk_trust_system(const char *host, const uint8_t *const *derv,
		      const size_t *lenv, size_t count, bsk_trust_cert_h *certh,
		      void *arg);
#if !defined(__APPLE__)
bool bsk_trust_verify(const char *const *ca_dirs, size_t dir_count,
		      const char *host, const uint8_t *const *derv,
		      const size_t *lenv, size_t count, bsk_trust_cert_h *certh,
		      void *arg);
#endif

static const char *ca_dirs[2];
static size_t ca_dir_count;


static bool trust(const char *host, const uint8_t *const *derv,
		  const size_t *lenv, size_t count, bsk_trust_cert_h *certh,
		  void *arg)
{
#if !defined(__APPLE__)
	if (ca_dir_count)
		return bsk_trust_verify(ca_dirs, ca_dir_count, host, derv, lenv,
					count, certh, arg);
#endif

	return bsk_trust_system(host, derv, lenv, count, certh, arg);
}

struct visit {
	const uint8_t *der;
	size_t len;
	int seen;
	int count;
};


static void visit(const uint8_t *der, size_t len, void *arg)
{
	struct visit *v = arg;

	v->count++;
	if (len == v->len && 0 == memcmp(der, v->der, len))
		v->seen++;
}


static uint8_t *read_der(const char *path, size_t *len)
{
	FILE *f = fopen(path, "rb");
	uint8_t *buf;
	long n;

	CHECK(f != NULL);
	fseek(f, 0, SEEK_END);
	n = ftell(f);
	fseek(f, 0, SEEK_SET);
	CHECK(n > 0);
	buf = malloc((size_t)n);
	CHECK(buf != NULL);
	CHECK(fread(buf, 1, (size_t)n, f) == (size_t)n);
	fclose(f);
	*len = (size_t)n;
	return buf;
}


int main(int argc, char **argv)
{
	const uint8_t *derv[MAX_CERTS];
	uint8_t *owned[MAX_CERTS];
	size_t lenv[MAX_CERTS];
	size_t count = 0;
	struct visit v = { NULL, 0, 0, 0 };
	bool unvisited = false;
	int first = 2;
	int i;

	while (argc > first - 1 && 0 == strncmp(argv[first - 1], "--", 2)) {
		if (0 == strcmp(argv[first - 1], "--unvisited")) {
			unvisited = true;
			first++;
		} else if (0 == strcmp(argv[first - 1], "--ca-dir") &&
			   argc > first && ca_dir_count < 2) {
			ca_dirs[ca_dir_count++] = argv[first];
			first += 2;
		} else {
			break;
		}
	}

	if (argc < first + 1 || argc - first > MAX_CERTS) {
		fprintf(stderr,
			"usage: %s [--unvisited] [--ca-dir DIR]... HOST "
			"LEAF.der [CA.der...]\n",
			argv[0]);
		return 2;
	}

	for (i = first; i < argc; i++) {
		owned[count] = read_der(argv[i], &lenv[count]);
		derv[count] = owned[count];
		count++;
	}

	v.der = derv[count - 1];
	v.len = lenv[count - 1];
	CHECK(trust(argv[first - 1], derv, lenv, count, visit, &v));
	CHECK(v.count > 0);
	if (unvisited) {
		CHECK(v.seen == 0);
	}
	CHECK(!trust("other.invalid", derv, lenv, count, NULL, NULL));
	CHECK(!trust("192.0.2.1", derv, lenv, count, NULL, NULL));
	CHECK(!trust("", derv, lenv, count, NULL, NULL));
	lenv[0] /= 2;
	v.count = 0;
	CHECK(!trust(argv[first - 1], derv, lenv, count, visit, &v));
	CHECK(v.count == 0);

	for (i = 0; i < (int)count; i++)
		free(owned[i]);

	if (unvisited)
		printf("trust: PASS (%s accepted; a certificate appended to "
		       "the chain is not part of the verified chain)\n",
		       argv[first - 1]);
	else
		printf("trust: PASS (%s accepted, other names and a cut chain "
		       "rejected)\n",
		       argv[first - 1]);
	return 0;
}
