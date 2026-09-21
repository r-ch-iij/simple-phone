/**
 * @file rtpwatch.c RTP 受信ウォッチドッグ用 aufilt
 *
 * デコードフィルタとして RX フレーム毎に最終受信時刻を記録する。
 * 音声データには一切触れない（パススルー）。
 *
 * 背景: 相手プロセス kill・クラッシュ時は BYE が送出されず、
 * PBX にチャネルが残留する。Asterisk 側 rtp_timeout は本環境で
 * 発火しないため、端末側で死亡検出する。
 * - RTP 受信が RTCP と違い 50pps で来るため検出が確実
 * - Opus DTX は無効化しているため、ミュート時も無音 RTP が
 *   流れ続け、誤爆しない（無音≠死亡）
 * - RTCP 受信時も時刻更新する（native-sip-jni.c 側）
 *
 * 参照側: native-sip-jni.c の watchdog_thread が
 * rtpwatch_last_rx_s() をポーリングする。
 */

#include <re.h>
#include <rem.h>
#include <baresip.h>
#include <time.h>

static volatile uint32_t last_rx_s = 0;


uint32_t rtpwatch_last_rx_s(void)
{
	return last_rx_s;
}


void rtpwatch_mark_rx(void)
{
	last_rx_s = (uint32_t)time(NULL);
}


struct rtpwatch_dec {
	struct aufilt_dec_st af;  /* base class */
};


static void dec_destructor(void *arg)
{
	struct rtpwatch_dec *st = arg;

	list_unlink(&st->af.le);
}


static int decode_update(struct aufilt_dec_st **stp, void **ctx,
			 const struct aufilt *af, struct aufilt_prm *prm,
			 const struct audio *au)
{
	struct rtpwatch_dec *st;

	(void)ctx;
	(void)af;
	(void)prm;
	(void)au;

	if (!stp)
		return EINVAL;

	st = mem_zalloc(sizeof(*st), dec_destructor);
	if (!st)
		return ENOMEM;

	*stp = st;

	return 0;
}


/* 受信フレーム毎に時刻を記録する（データは変更しない） */
static int decode_frame(struct aufilt_dec_st *st, struct auframe *af)
{
	(void)st;
	(void)af;

	rtpwatch_mark_rx();

	return 0;
}


// 送信側は監視しないため encode ハンドラは登録しない（decode-only）。
static struct aufilt rtpwatch = {
	.name    = "rtpwatch",
	.decupdh = decode_update,
	.dech    = decode_frame
};


static int module_init(void)
{
	aufilt_register(baresip_aufiltl(), &rtpwatch);

	return 0;
}


static int module_close(void)
{
	aufilt_unregister(&rtpwatch);

	return 0;
}


EXPORT_SYM const struct mod_export DECL_EXPORTS(rtpwatch) = {
	"rtpwatch",
	"filter",
	module_init,
	module_close
};
