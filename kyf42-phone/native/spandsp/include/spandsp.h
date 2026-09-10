/*
 * Minimal spandsp umbrella header for KYF42 SimplePhone.
 *
 * Only the G.722 subset is vendored (see native/spandsp/).
 * Upstream: https://github.com/freeswitch/spandsp (LGPL-2.1)
 */
#if !defined(_SPANDSP_H_)
#define _SPANDSP_H_

#include <inttypes.h>
#include <spandsp/telephony.h>
#include <spandsp/alloc.h>
#include <spandsp/g722.h>

#if defined(SPANDSP_EXPOSE_INTERNAL_STRUCTURES)
#include <spandsp/private/g722.h>
#endif

#endif
