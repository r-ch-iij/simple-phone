/**
 * @file opensles.h  OpenSLES audio driver -- internal API
 *
 * Copyright (C) 2010 Alfred E. Heggestad
 */


extern SLObjectItf engineObject;
extern SLEngineItf engineEngine;


/* 再生音量ゲイン（PCM デジタル倍率）。1.0 = 等倍、>1.0 = ブースト */
void opensles_set_gain(float gain);
float opensles_get_gain(void);


int opensles_player_alloc(struct auplay_st **stp, const struct auplay *ap,
			  struct auplay_prm *prm, const char *device,
			  auplay_write_h *wh, void *arg);
int opensles_recorder_alloc(struct ausrc_st **stp, const struct ausrc *as,
			    struct ausrc_prm *prm, const char *device,
			    ausrc_read_h *rh, ausrc_error_h *errh, void *arg);
