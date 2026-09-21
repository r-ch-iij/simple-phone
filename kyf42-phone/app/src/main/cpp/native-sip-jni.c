#include <jni.h>
#include <android/log.h>
#include <string.h>
#include <time.h>
#include <unistd.h>
#include <pthread.h>
#include <stdbool.h>
#include <re.h>
#include <rem.h>
#include <baresip.h>

#define TAG "NativeSip"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

// g711 コーデック
extern struct aucodec pcmu;
extern struct aucodec pcma;

// opensles オーディオバックエンド（Android OpenSL ES）
extern const struct mod_export exports_opensles;
// opus コーデック
extern const struct mod_export exports_opus;
// g722 コーデック（spandsp 使用。conf 不要）
// 注意: G.722 は 16kHz 音声。端末 8kHz との変換は auresamp が行う
extern const struct mod_export exports_g722;
// サンプルレート変換（8kHz 端末 ⇔ 48kHz Opus 等の橋渡し）
extern const struct mod_export exports_auresamp;
// サンプルフォーマット変換
extern const struct mod_export exports_auconv;
// RTP 受信ウォッチドッグ（自作 aufilt。RX フレーム毎に時刻を記録）
// 注意: static ビルドでは mod_table に載せるだけでは初期化されない。
// conf_modules() を使っていないため、各モジュールの init() を直接呼ぶ
// （opensles/opus と同じ方式）
extern const struct mod_export exports_rtpwatch;
// opensles 音量ゲイン設定（PCM デジタル倍率）
void opensles_set_gain(float gain);

static JavaVM *g_vm = NULL;
static jobject g_callback = NULL;
static struct baresip *g_baresip = NULL;
static struct ua *g_ua = NULL;
static int g_registered = 0;
static int g_in_call = 0;
// 現在の通話（単一回線運用: 複数通話の並存を防ぐ）
static struct call *g_call = NULL;
// メディア生存監視（rtpwatch.c）。
// 相手プロセス kill・クラッシュ時は RTP/RTCP が止まるため、途絶したら
// 自発的に hangup する（BYE を送出して PBX の両 leg を解消する）。
// - RTP 受信フック（50pps）で検出。RTCP は SR 受信時のみイベント化
//   されるため疎で、主信号には使えない（補助として時刻更新のみ）
// - Opus DTX は無効化しているため、ミュート時も無音 RTP が流れ続け
//   誤爆しない（無音≠死亡）
// - Asterisk 側 rtp_timeout は本環境で発火しないことを確認済み
uint32_t rtpwatch_last_rx_s(void);
void rtpwatch_mark_rx(void);
// メディア途絶タイムアウト秒（通常 50pps 受信に対する余裕）
#define RX_TIMEOUT_S 30
static volatile int g_watchdog_running = 0;
static pthread_t g_re_thread;
static volatile int g_re_running = 0;
// baresip はプロセス内で一度だけ初期化し、サービス再起動では解体しない
static int g_initialized = 0;

// SIP 設定
static char SIP_SERVER_STR[64] = "";
static char SIP_USER_STR[32] = "";
static char SIP_PASS_STR[64] = "";
static char SIP_REALM_STR[32] = "";
static int SIP_PORT_INT = 5060;

// ========== コールバックヘルパー ==========
static void call_callback_str(const char *method, const char *msg) {
    if (g_callback == NULL) return;
    JNIEnv *env;
    int getEnvStat = (*g_vm)->GetEnv(g_vm, (void **)&env, JNI_VERSION_1_6);
    if (getEnvStat == JNI_EDETACHED) {
        (*g_vm)->AttachCurrentThread(g_vm, &env, NULL);
    }
    if (env == NULL) return;
    jclass cls = (*env)->GetObjectClass(env, g_callback);
    jmethodID mid = (*env)->GetMethodID(env, cls, method, "(Ljava/lang/String;)V");
    if (mid) {
        jstring jmsg = (*env)->NewStringUTF(env, msg);
        (*env)->CallVoidMethod(env, g_callback, mid, jmsg);
        (*env)->DeleteLocalRef(env, jmsg);
    }
}

static void call_callback_void(const char *method) {
    if (g_callback == NULL) return;
    JNIEnv *env;
    int getEnvStat = (*g_vm)->GetEnv(g_vm, (void **)&env, JNI_VERSION_1_6);
    if (getEnvStat == JNI_EDETACHED) {
        (*g_vm)->AttachCurrentThread(g_vm, &env, NULL);
    }
    if (env == NULL) return;
    jclass cls = (*env)->GetObjectClass(env, g_callback);
    jmethodID mid = (*env)->GetMethodID(env, cls, method, "()V");
    if (mid) (*env)->CallVoidMethod(env, g_callback, mid);
}

// ========== baresip ログ転送（logcat 可視化） ==========
// baresip の info/warning/debug はデフォルトで stdout のみ。
// Android では見えないため logcat に転送する（TX 無音等の調査に必須）
static void bs_log_handler(uint32_t level, const char *msg) {
    int prio;
    switch (level) {
    case LEVEL_DEBUG: prio = ANDROID_LOG_DEBUG; break;
    case LEVEL_WARN:  prio = ANDROID_LOG_WARN; break;
    case LEVEL_ERROR: prio = ANDROID_LOG_ERROR; break;
    default:          prio = ANDROID_LOG_INFO; break;
    }
    __android_log_print(prio, "baresip", "%s", msg ? msg : "(null)");
}

static struct log g_bs_log = { .h = bs_log_handler };

static void init_bs_log(void) {
    static int done = 0;
    if (done)
        return;
    done = 1;
    log_register_handler(&g_bs_log);
    log_enable_debug(true);
    LOGI("baresip log handler registered");
}

// ========== baresip イベントハンドラ ==========
static void ensure_re_async(void);
static void watchdog_start(void);

static void baresip_event_handler(struct ua *ua, enum ua_event ev,
                                  struct call *call, const char *prm,
                                  void *arg) {
    (void)arg;
    (void)ua;

    // re_main スレッド上で最初のネットワークイベントが来た時点で
    // re_thread_async を初期化する（fd_listen のスレッドチェック回避のため
    // 必ず re_main スレッドから行う）
    ensure_re_async();

    const char *ev_name = uag_event_str(ev);
    LOGI("event: %s %s", ev_name, prm ? prm : "");

    switch (ev) {
    case UA_EVENT_REGISTER_OK:
        g_registered = 1;
        call_callback_void("onRegistered");
        break;
    case UA_EVENT_REGISTER_FAIL:
        g_registered = 0;
        call_callback_str("onRegistrationFailed",
                          prm ? prm : "registration failed");
        break;
    case UA_EVENT_CALL_OUTGOING:
        g_call = call;
        g_in_call = 1;
        break;
    case UA_EVENT_CALL_INCOMING:
        // 通話中に新着信: 単一回線運用のためビジー拒否
        if (g_call && g_call != call) {
            LOGI("busy: rejecting incoming call (existing call active)");
            ua_hangup(g_ua, call, 486, "Busy");
            break;
        }
        g_call = call;
        g_in_call = 1;
        // prm は "sip:203@192.0.2.1" 形式。ユーザー名だけ抽出して渡す。
        {
            const char *p = prm ? prm : "";
            const char *user_start = strstr(p, "sip:");
            if (user_start) user_start += 4; else user_start = p;
            const char *at = strchr(user_start, '@');
            char user_buf[32];
            if (at && (size_t)(at - user_start) < sizeof(user_buf)) {
                memcpy(user_buf, user_start, at - user_start);
                user_buf[at - user_start] = '\0';
                call_callback_str("onIncomingCall", user_buf);
            } else {
                call_callback_str("onIncomingCall", user_start);
            }
        }
        break;
    case UA_EVENT_CALL_ESTABLISHED:
        g_call = call;
        g_in_call = 1;
        // 通話開始を起点にメディア監視を開始する
        rtpwatch_mark_rx();
        watchdog_start();
        call_callback_void("onCallStarted");
        break;
    case UA_EVENT_CALL_RTCP:
        // RTCP 受信（SR/APP のみイベント化されるため疎）。補助信号として更新
        rtpwatch_mark_rx();
        break;
    case UA_EVENT_CALL_CLOSED:
        LOGI("CALL_CLOSED: g_call=%p call=%p match=%d", g_call, call, g_call == call);
        if (g_call == call || g_call != NULL) {
            // ポインタ一致 or 不問で通話終了処理
            // （リモート終話・多重通話解消の両対応）
            g_call = NULL;
            g_in_call = 0;
            call_callback_void("onCallEnded");
        }
        break;
    case UA_EVENT_AUDIO_ERROR:
        call_callback_str("onCallFailed", prm ? prm : "通話エラー");
        break;
    default:
        break;
    }
}

// ========== baresip 設定ファイル生成 ==========

// ========== re_main スレッドでの実行 ==========
// baresip の ua_* / call_* API は re_main（イベントループ）スレッドから
// 呼ぶ必要がある。別スレッド（JNI スレッド）から直接呼ぶと、イベントループとの
// 競合で解放済みメモリへのアクセス（use-after-free）によるクラッシュを起こす。
// re_thread_async で処理を re_main スレッドに転送する。

typedef void (*remain_h)(void *arg);

struct remain_post {
    remain_h handler;
    void *arg; // handler 内で mem_deref すること
};

static int g_re_async_ready = 0;

static int wait_for_re_main_ready(int timeout_ms) {
    int waited_ms = 0;

    while (!g_re_running && waited_ms < timeout_ms) {
        usleep(10000);
        waited_ms += 10;
    }

    return g_re_running ? 0 : ETIMEDOUT;
}

static void build_server_authority(char *buf, size_t size) {
    const bool server_has_port = strchr(SIP_SERVER_STR, ':') != NULL;

    if (server_has_port) {
        snprintf(buf, size, "%s", SIP_SERVER_STR);
    } else {
        snprintf(buf, size, "%s:%d", SIP_SERVER_STR, SIP_PORT_INT);
    }
}

static void build_account_aor(char *buf, size_t size) {
    char authority[96];

    build_server_authority(authority, sizeof(authority));
    snprintf(buf, size,
             "<sip:%s@%s>;auth_user=%s;auth_pass=%s;regint=60",
             SIP_USER_STR, authority, SIP_USER_STR, SIP_PASS_STR);
}

static void ensure_re_async(void) {
    if (g_re_async_ready)
        return;
    if (re_thread_check(false) != 0)
        return;
    if (re_thread_async_init(1) == 0) {
        g_re_async_ready = 1;
        LOGI("re_thread_async initialized");
    } else {
        LOGW("re_thread_async_init failed");
    }
}

static void remain_cb(int err, void *arg) {
    (void)err;
    struct remain_post *p = arg;
    if (p->handler)
        p->handler(p->arg);
    mem_deref(p);
}

// handler(arg) を re_main スレッド上で実行する。
// 失敗時（async 未初期化等）は 0 以外を返す。
static int remain_run(remain_h handler, void *arg) {
    struct remain_post *p = mem_zalloc(sizeof(*p), NULL);
    if (!p)
        return ENOMEM;
    p->handler = handler;
    p->arg = arg;
    int err = re_thread_async_main(NULL, remain_cb, p);
    if (err) {
        LOGE("remain_run: re_thread_async_main failed: %d", err);
        mem_deref(p);
    }
    return err;
}

// ========== re_main スレッドで実行する各操作 ==========

static void register_worker(void *arg) {
    (void)arg;
    struct ua *ua = NULL;
    char aor[256];
    char authority[96];

    build_account_aor(aor, sizeof(aor));
    build_server_authority(authority, sizeof(authority));
    LOGI("register_worker: user=%s authority=%s", SIP_USER_STR, authority);

    if (g_ua) {
        LOGI("register_worker: ua already exists, skip");
        return;
    }

    int err = ua_alloc(&g_ua, aor);
    if (err) {
        LOGE("ua_alloc failed: %d", err);
        call_callback_str("onRegistrationFailed", "UA作成エラー");
        return;
    }

    err = ua_register(g_ua);
    if (err) {
        LOGE("ua_register failed: %d", err);
        call_callback_str("onRegistrationFailed", "登録エラー");
        return;
    }

    LOGI("register_worker: registration started");
    call_callback_str("onDebug", "SIP 登録中...");
}

static void unregister_worker(void *arg) {
    (void)arg;
    if (g_ua)
        ua_unregister(g_ua);
}

static void *re_main_thread(void *arg);
static void reregister_worker(void *arg) {
    char *aor_str = arg;

    if (g_ua) {
        ua_unregister(g_ua);
        ua_destroy(g_ua);
        g_ua = NULL;
    }
    g_call = NULL;
    g_in_call = 0;

    int err = ua_alloc(&g_ua, aor_str);
    if (err) {
        LOGE("reregister_worker: ua_alloc failed: %d", err);
        call_callback_str("onRegistrationFailed", "UA作成エラー");
    } else {
        err = ua_register(g_ua);
        if (err) {
            LOGE("reregister_worker: ua_register failed: %d", err);
            call_callback_str("onRegistrationFailed", "登録エラー");
        } else {
            LOGI("reregister_worker: registration started");
            call_callback_str("onDebug", "SIP 再登録中...");
        }
    }

    mem_deref(aor_str);
}

static void makecall_worker(void *arg) {
    char *uri = arg;

    if (g_ua) {
        // 既存の通話があれば先に終話する（複数通話の並存を防ぐ）
        if (g_call) {
            LOGI("ending existing call before new call");
            ua_hangup(g_ua, g_call, 0, NULL);
            g_call = NULL;
            g_in_call = 0;
            // 少し待って CALL_CLOSED を処理させる
            // （baresip のイベントループで終了処理が走るのを待つ）
            usleep(200000);  // 200ms
        }
        struct call *call = NULL;
        int err = ua_connect(g_ua, &call, NULL, uri, VIDMODE_OFF);
        if (err) {
            LOGE("ua_connect failed: %d", err);
            call_callback_str("onCallFailed", "発信エラー");
        } else {
            LOGI("ua_connect succeeded: call=%p", call);
        }
    }

    mem_deref(uri);
}

static void answer_worker(void *arg) {
    (void)arg;
    if (g_call)
        ua_answer(g_ua, g_call, VIDMODE_OFF);
}

static void endcall_worker(void *arg) {
    (void)arg;
    if (g_call) {
        ua_hangup(g_ua, g_call, 0, NULL);
        g_call = NULL;
    }
    g_in_call = 0;
}

// RTCP タイムアウト時の hangup（re_main スレッド上で実行）。
// CALL_CLOSED が発火して既存フローで終話・BYE 送出される。
static void timeout_hangup_worker(void *arg) {
    (void)arg;
    if (g_call) {
        LOGI("timeout_hangup_worker: media timeout, hanging up");
        ua_hangup(g_ua, g_call, 0, "Media timeout");
        g_call = NULL;
    }
    g_in_call = 0;
}

// メディア生存監視スレッド（detached）。通話中に RTP/RTCP 受信が
// 途絶えたら re_main 経由で hangup する。通話終了で自発的に抜ける。
static void *watchdog_thread(void *arg) {
    (void)arg;
    LOGI("watchdog: started (timeout=%ds)", RX_TIMEOUT_S);
    for (;;) {
        sleep(5);
        if (g_call == NULL)
            break;
        uint32_t silent = (uint32_t)time(NULL) - rtpwatch_last_rx_s();
        if (silent > RX_TIMEOUT_S) {
            LOGW("watchdog: no media for %us, hanging up", silent);
            if (remain_run(timeout_hangup_worker, NULL) != 0) {
                // re_main 転送失敗時は監視を継続（次回リトライ）
                continue;
            }
            break;
        }
    }
    g_watchdog_running = 0;
    LOGI("watchdog: stopped");
    return NULL;
}

static void watchdog_start(void) {
    if (g_watchdog_running)
        return;
    g_watchdog_running = 1;
    pthread_t tid;
    pthread_attr_t attr;
    pthread_attr_init(&attr);
    pthread_attr_setdetachstate(&attr, PTHREAD_CREATE_DETACHED);
    if (pthread_create(&tid, &attr, watchdog_thread, NULL) != 0) {
        LOGE("watchdog_start: pthread_create failed");
        g_watchdog_running = 0;
    }
    pthread_attr_destroy(&attr);
}

static void dtmf_worker(void *arg) {
    intptr_t digit = (intptr_t)arg;
    if (g_call)
        call_send_digit(g_call, (char)digit);
}

static void mute_worker(void *arg) {
    intptr_t muted = (intptr_t)arg;
    if (g_call) {
        // 送信音声を無音にする（audio_mute は TX 側フレームをゼロ化する）
        audio_mute(call_audio(g_call), muted ? true : false);
        LOGI("mute_worker: muted=%d", (int)muted);
    } else {
        LOGW("mute_worker: no active call");
    }
}

// ========== re_main スレッド ==========
static void *re_main_thread(void *arg) {
    (void)arg;
    LOGI("re_main: event loop started");
    g_re_running = 1;
    re_main(NULL);
    g_re_running = 0;
    LOGI("re_main: event loop stopped");
    return NULL;
}

// ========== 初期化ヘルパー ==========

static int init_libre(void) {
    int err = libre_init();
    if (err) {
        LOGE("libre_init failed: %d", err);
        return err;
    }
    LOGI("libre_init succeeded, thread=%lu", (unsigned long)pthread_self());

    re_thread_init();
    int re_async_err = re_thread_async_init(1);
    if (re_async_err == 0) {
        g_re_async_ready = 1;
        LOGI("re_thread_async initialized");
    } else {
        LOGW("re_thread_async_init failed: %d", re_async_err);
    }
    return 0;
}

static int init_baresip(void) {
    struct config *cfg = conf_config();
    if (!cfg) {
        LOGE("conf_config() returned NULL");
        return -1;
    }
    memset(cfg, 0, sizeof(*cfg));
    cfg->net.af = AF_INET;
    cfg->audio.srate_play = 8000;
    cfg->audio.srate_src = 8000;
    cfg->audio.channels_play = 1;
    cfg->audio.channels_src = 1;
    cfg->avt.rtp_ports.min = 10000;
    cfg->avt.rtp_ports.max = 20000;

    int err = baresip_init(cfg);
    if (err) {
        LOGE("baresip_init failed: %d", err);
        return err;
    }
    return 0;
}

static int init_audio(void) {
    // 注: aucodec 登録順＝SDP オファー順。
    // G.722（広帯域・高音質）→ Opus（低ビットレート）→ G.711 の順で優先させる。
    // g722 モジュール初期化（conf 不要。static ビルドのため直接 init）
    int g722_err = exports_g722.init ? exports_g722.init() : -1;
    if (g722_err)
        LOGW("g722 module init failed: %d (non-fatal)", g722_err);
    else
        LOGI("g722 codec registered");
    // Opus を携帯音声向けに調整する（低負荷・低ビットレート）。
    // conf ファイルを使わないため conf_configure_buf で直接流し込む。
    // stereo=0 で mono 化、bitrate 20kbps、complexity 5、VOIP、
    // DTX は無効（無音時も RTP を送り続け、死亡検出の誤爆を防ぐ）、FEC 有効。
    static const char opus_conf[] =
        "audio_buffer 20-160\n"
        "opus_stereo no\n"
        "opus_sprop_stereo no\n"
        "opus_bitrate 20000\n"
        "opus_cbr no\n"
        "opus_inbandfec yes\n"
        "opus_dtx no\n"
        "opus_complexity 5\n"
        "opus_application voip\n"
        "opus_packet_loss 5\n";
    if (conf_configure_buf((const uint8_t *)opus_conf, sizeof(opus_conf) - 1) != 0) {
        LOGW("opus conf setup failed, using module defaults");
    }

    // opus モジュール初期化（上記 conf を読む）
    int opus_err = exports_opus.init ? exports_opus.init() : -1;
    if (opus_err)
        LOGW("opus module init failed: %d (non-fatal)", opus_err);
    else
        LOGI("opus codec registered");

    // aufilt 初期化（登録順＝フィルタチェイン順）。
    // 8kHz 端末と 48kHz Opus の橋渡しに必須。static ビルドでは
    // 自動ロードされないため直接 init する
    int rs_err = exports_auresamp.init ? exports_auresamp.init() : -1;
    LOGI("auresamp init: %d", rs_err);
    int cv_err = exports_auconv.init ? exports_auconv.init() : -1;
    LOGI("auconv init: %d", cv_err);
    int rw_err = exports_rtpwatch.init ? exports_rtpwatch.init() : -1;
    LOGI("rtpwatch init: %d", rw_err);
    LOGI("aufilt count: %u", list_count(baresip_aufiltl()));

    // G.711 は G.722/Opus の後に登録（オファー順で劣後）
    aucodec_register(baresip_aucodecl(), &pcmu);
    aucodec_register(baresip_aucodecl(), &pcma);
    LOGI("g711 codecs (PCMU/PCMA) registered");

    int oerr = exports_opensles.init ? exports_opensles.init() : -1;
    if (oerr)
        LOGW("opensles audio module init failed: %d", oerr);
    else
        LOGI("opensles audio module registered");
    return 0;
}

// ========== JNI 初期化 ==========
JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    g_vm = vm;
    LOGI("JNI_OnLoad: NativeSip initialized");
    return JNI_VERSION_1_6;
}

// ========== SIP 操作 ==========

JNIEXPORT void JNICALL
Java_io_github_r_1ch_1iij_simplephone_NativeSip_nativeInit(
    JNIEnv *env, jobject thiz,
    jstring server, jint port, jstring user, jstring pass, jstring realm) {

    const char *server_str = (*env)->GetStringUTFChars(env, server, NULL);
    const char *user_str = (*env)->GetStringUTFChars(env, user, NULL);
    const char *pass_str = (*env)->GetStringUTFChars(env, pass, NULL);
    const char *realm_str = (*env)->GetStringUTFChars(env, realm, NULL);

    LOGI("nativeInit: server=%s:%d user=%s realm=%s", server_str, port, user_str, realm_str);

    // 既に初期化済み（サービス再起動等）なら再初期化せず戻る
    if (g_initialized) {
        LOGI("nativeInit: already initialized, skip");
        (*env)->ReleaseStringUTFChars(env, server, server_str);
        (*env)->ReleaseStringUTFChars(env, user, user_str);
        (*env)->ReleaseStringUTFChars(env, pass, pass_str);
        (*env)->ReleaseStringUTFChars(env, realm, realm_str);
        return;
    }

    // 設定を保存
    strncpy(SIP_SERVER_STR, server_str, sizeof(SIP_SERVER_STR) - 1);
    strncpy(SIP_USER_STR, user_str, sizeof(SIP_USER_STR) - 1);
    strncpy(SIP_PASS_STR, pass_str, sizeof(SIP_PASS_STR) - 1);
    strncpy(SIP_REALM_STR, realm_str, sizeof(SIP_REALM_STR) - 1);
    SIP_PORT_INT = port;

    // libre 初期化（先にログ転送を有効化して警告を可視化する）
    init_bs_log();
    if (init_libre()) {
        call_callback_str("onRegistrationFailed", "libre初期化エラー");
        goto cleanup;
    }

    // baresip 初期化
    if (init_baresip()) {
        call_callback_str("onRegistrationFailed", "baresip初期化エラー");
        goto cleanup;
    }

    // オーディオ初期化
    init_audio();

    // SIP スタック初期化（UDP, TCP, TLS）
    int err = ua_init("SimplePhone/1.0", true, true, false);
    if (err) {
        LOGE("ua_init failed: %d", err);
        call_callback_str("onRegistrationFailed", "SIPスタック初期化エラー");
        goto cleanup;
    }

    // イベントハンドラ登録
    uag_event_register(baresip_event_handler, NULL);

    // re_main イベントループを別スレッドで開始
    err = pthread_create(&g_re_thread, NULL, re_main_thread, NULL);
    if (err) {
        LOGE("pthread_create failed: %d", err);
    } else if (wait_for_re_main_ready(500) != 0) {
        LOGW("nativeInit: re_main did not become ready within 500ms");
    }

    LOGI("nativeInit: baresip initialized successfully");
    g_initialized = 1;

cleanup:
    (*env)->ReleaseStringUTFChars(env, server, server_str);
    (*env)->ReleaseStringUTFChars(env, user, user_str);
    (*env)->ReleaseStringUTFChars(env, pass, pass_str);
    (*env)->ReleaseStringUTFChars(env, realm, realm_str);
}

JNIEXPORT void JNICALL
Java_io_github_r_1ch_1iij_simplephone_NativeSip_nativeDestroy(JNIEnv *env, jobject thiz) {
    // baresip はプロセス内で一度だけ初期化し、サービス再起動では解体しない。
    // 解体はプロセス終了時に OS が回収する。これにより再起動チャーンでの
    // メモリ破壊（SIGBUS）を回避する。
    LOGI("nativeDestroy: no-op (baresip kept alive for process lifetime)");
    // コールバックのみ無効化
    if (g_callback != NULL) {
        (*env)->DeleteGlobalRef(env, g_callback);
        g_callback = NULL;
    }
}

JNIEXPORT void JNICALL
Java_io_github_r_1ch_1iij_simplephone_NativeSip_nativeRegister(JNIEnv *env, jobject thiz) {
    LOGI("nativeRegister");
    if (wait_for_re_main_ready(500) != 0) {
        LOGE("nativeRegister: re_main not ready");
        call_callback_str("onRegistrationFailed", "SIP起動待ちタイムアウト");
        return;
    }
    int err = remain_run(register_worker, NULL);
    if (err) {
        LOGE("nativeRegister: remain_run failed: %d", err);
        call_callback_str("onRegistrationFailed", "登録キュー投入エラー");
    }
}

JNIEXPORT void JNICALL
Java_io_github_r_1ch_1iij_simplephone_NativeSip_nativeUnregister(JNIEnv *env, jobject thiz) {
    LOGI("nativeUnregister");
    int err = remain_run(unregister_worker, NULL);
    if (err) {
        LOGW("nativeUnregister: remain_run failed (%d), running directly", err);
        unregister_worker(NULL);
    }
}

// アカウント設定を変更して再登録（baresip/libre の再初期化は行わない）
JNIEXPORT void JNICALL
Java_io_github_r_1ch_1iij_simplephone_NativeSip_nativeReregister(
    JNIEnv *env, jobject thiz, jstring aor) {
    const char *aor_str = (*env)->GetStringUTFChars(env, aor, NULL);
    LOGI("nativeReregister: scheduling re-registration");

    char *aor_dup = NULL;
    if (str_dup(&aor_dup, aor_str)) {
        LOGE("nativeReregister: str_dup failed");
        (*env)->ReleaseStringUTFChars(env, aor, aor_str);
        return;
    }

    if (wait_for_re_main_ready(500) != 0) {
        LOGE("nativeReregister: re_main not ready");
        mem_deref(aor_dup);
        (*env)->ReleaseStringUTFChars(env, aor, aor_str);
        call_callback_str("onRegistrationFailed", "SIP起動待ちタイムアウト");
        return;
    }

    int err = remain_run(reregister_worker, aor_dup);
    if (err) {
        LOGE("nativeReregister: remain_run failed: %d", err);
        mem_deref(aor_dup);
        call_callback_str("onRegistrationFailed", "再登録キュー投入エラー");
    }

    (*env)->ReleaseStringUTFChars(env, aor, aor_str);
}

JNIEXPORT void JNICALL
Java_io_github_r_1ch_1iij_simplephone_NativeSip_nativeMakeCall(
    JNIEnv *env, jobject thiz, jstring number) {
    const char *number_str = (*env)->GetStringUTFChars(env, number, NULL);
    LOGI("nativeMakeCall: %s", number_str);

    char uri_buf[256];
    char authority[96];
    // 既に sip: で始まっている場合はそのまま、否则は sip:user@server を組み立てる
    if (strncmp(number_str, "sip:", 4) == 0 || strncmp(number_str, "tel:", 4) == 0) {
        snprintf(uri_buf, sizeof(uri_buf), "%s", number_str);
    } else {
        build_server_authority(authority, sizeof(authority));
        snprintf(uri_buf, sizeof(uri_buf), "sip:%s@%s", number_str, authority);
    }

    char *uri = NULL;
    if (str_dup(&uri, uri_buf)) {
        LOGE("nativeMakeCall: str_dup failed");
        (*env)->ReleaseStringUTFChars(env, number, number_str);
        return;
    }

    int err = remain_run(makecall_worker, uri);
    if (err) {
        LOGW("nativeMakeCall: remain_run failed (%d), running directly", err);
        makecall_worker(uri);
    }

    (*env)->ReleaseStringUTFChars(env, number, number_str);
}

JNIEXPORT void JNICALL
Java_io_github_r_1ch_1iij_simplephone_NativeSip_nativeAnswerCall(JNIEnv *env, jobject thiz) {
    LOGI("nativeAnswerCall");
    int err = remain_run(answer_worker, NULL);
    if (err) {
        LOGW("nativeAnswerCall: remain_run failed (%d), running directly", err);
        answer_worker(NULL);
    }
}

JNIEXPORT void JNICALL
Java_io_github_r_1ch_1iij_simplephone_NativeSip_nativeEndCall(JNIEnv *env, jobject thiz) {
    LOGI("nativeEndCall");
    int err = remain_run(endcall_worker, NULL);
    if (err) {
        LOGW("nativeEndCall: remain_run failed (%d), running directly", err);
        endcall_worker(NULL);
    }
}

JNIEXPORT void JNICALL
Java_io_github_r_1ch_1iij_simplephone_NativeSip_nativeSendDtmf(
    JNIEnv *env, jobject thiz, jchar digit) {
    LOGI("nativeSendDtmf: %c", digit);
    int err = remain_run(dtmf_worker, (void *)(intptr_t)digit);
    if (err) {
        LOGW("nativeSendDtmf: remain_run failed (%d), running directly", err);
        dtmf_worker((void *)(intptr_t)digit);
    }
}

JNIEXPORT void JNICALL
Java_io_github_r_1ch_1iij_simplephone_NativeSip_nativeSetMute(
    JNIEnv *env, jobject thiz, jboolean mute) {
    LOGI("nativeSetMute: %d", mute ? 1 : 0);
    int err = remain_run(mute_worker, (void *)(intptr_t)(mute ? 1 : 0));
    if (err) {
        LOGW("nativeSetMute: remain_run failed (%d), running directly", err);
        mute_worker((void *)(intptr_t)(mute ? 1 : 0));
    }
}

JNIEXPORT jboolean JNICALL
Java_io_github_r_1ch_1iij_simplephone_NativeSip_nativeIsRegistered(JNIEnv *env, jobject thiz) {
    return g_registered ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_io_github_r_1ch_1iij_simplephone_NativeSip_nativeIsInCall(JNIEnv *env, jobject thiz) {
    return g_in_call ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_io_github_r_1ch_1iij_simplephone_NativeSip_nativeSetVolume(
    JNIEnv *env, jobject thiz, jfloat gain) {
    LOGI("nativeSetVolume: gain=%.2f", gain);
    opensles_set_gain(gain);
}

JNIEXPORT void JNICALL
Java_io_github_r_1ch_1iij_simplephone_NativeSip_nativeSetCallback(
    JNIEnv *env, jobject thiz, jobject callback) {
    if (g_callback != NULL) {
        (*env)->DeleteGlobalRef(env, g_callback);
    }
    g_callback = (*env)->NewGlobalRef(env, callback);
    LOGI("nativeSetCallback: callback set");
}
