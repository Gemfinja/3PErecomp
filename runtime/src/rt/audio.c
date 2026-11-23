/* Host audio backend: a 44.1 kHz stereo s16 mixing ring drained by winmm waveOut.
 *
 * The PSP mixes up to 8 sceAudio output channels in hardware; each guest channel that calls
 * sceAudioOutput*Blocking pushes its buffer here. Channels keep their own write cursor over a
 * shared int32 accumulator ring, so concurrently-playing channels overlap (sum) instead of
 * interleaving in time. A feeder thread clamps accumulated samples to s16 and hands fixed-size
 * blocks to waveOut; if the game outruns real time the push clamps (drops), if it falls behind
 * the feeder emits silence. This favours "keeps running, sounds right enough" over hi-fi sync —
 * the scheduler's virtual-time pacing of the *Blocking calls stays the authority on game speed.
 */

#define _CRT_SECURE_NO_WARNINGS
#ifdef _WIN32
#include <windows.h>
#include <mmsystem.h>
#else
#include <pthread.h>
#include <aaudio/AAudio.h>
#endif
#include <stdio.h>
#include <stdint.h>
#include <stdlib.h>

#define RING_FRAMES  (1 << 16)            /* 64k frames ≈ 1.5 s of headroom */
#define RING_MASK    (RING_FRAMES - 1)
#ifdef __ANDROID__
#define BLOCK_FRAMES 512                  /* ~11.6 ms OpenSL ES slice (4x finer than 1024) */
#define NUM_BLOCKS   6                    /* ~69 ms total hardware queue */
#else
#define BLOCK_FRAMES 1024                 /* waveOut block: ~23 ms */
#define NUM_BLOCKS   4                    /* device queue ~93 ms (keeps voice near subtitles) */
#endif

static int32_t  s_accL[RING_FRAMES], s_accR[RING_FRAMES];
static uint64_t s_play = 0;               /* feeder (read) cursor, frames */
static uint64_t s_chw[8];                 /* per-channel write cursors, frames */
#ifdef _WIN32
static CRITICAL_SECTION s_lock;
#define AUDIO_LOCK()   EnterCriticalSection(&s_lock)
#define AUDIO_UNLOCK() LeaveCriticalSection(&s_lock)
static HWAVEOUT s_wo = NULL;
static WAVEHDR  s_hdr[NUM_BLOCKS];
static int16_t  s_blk[NUM_BLOCKS][BLOCK_FRAMES * 2];
static HANDLE   s_event = NULL, s_thread = NULL;
static volatile int s_run = 0;
#else
static pthread_mutex_t s_lock = PTHREAD_MUTEX_INITIALIZER;
#define AUDIO_LOCK()   pthread_mutex_lock(&s_lock)
#define AUDIO_UNLOCK() pthread_mutex_unlock(&s_lock)
static AAudioStream *s_aa_stream = NULL;
static int16_t s_aa_last_l = 0, s_aa_last_r = 0;
static volatile int s_aa_reopening = 0;
#endif
static int s_inited = 0, s_ok = 0;

static int16_t clamp16(int32_t v) { return v < -32768 ? -32768 : v > 32767 ? 32767 : (int16_t)v; }

#ifdef _WIN32
static DWORD WINAPI feeder(LPVOID arg) {
    (void)arg;
    int next = 0;
    while (s_run) {
        WaitForSingleObject(s_event, 100);
        while (s_run) {
            WAVEHDR *h = &s_hdr[next];
            if (!(h->dwFlags & WHDR_DONE)) break;          /* block still queued */
            int16_t *out = s_blk[next];
            EnterCriticalSection(&s_lock);
            for (int i = 0; i < BLOCK_FRAMES; i++) {
                uint32_t idx = (uint32_t)((s_play + (uint64_t)i) & RING_MASK);
                out[i * 2 + 0] = clamp16(s_accL[idx]);
                out[i * 2 + 1] = clamp16(s_accR[idx]);
                s_accL[idx] = s_accR[idx] = 0;
            }
            s_play += BLOCK_FRAMES;
            LeaveCriticalSection(&s_lock);
            h->dwFlags &= ~WHDR_DONE;
            waveOutWrite(s_wo, h, sizeof(*h));
            next = (next + 1) % NUM_BLOCKS;
        }
    }
    return 0;
}

int sr_audio_init(void) {
    if (s_inited) return s_ok;
    s_inited = 1;
    if (getenv("SR_NOAUDIO")) return s_ok = 0;
    WAVEFORMATEX wf = {0};
    wf.wFormatTag = WAVE_FORMAT_PCM;
    wf.nChannels = 2;
    wf.nSamplesPerSec = 44100;
    wf.wBitsPerSample = 16;
    wf.nBlockAlign = 4;
    wf.nAvgBytesPerSec = 44100 * 4;
    s_event = CreateEventA(NULL, FALSE, FALSE, NULL);
    if (waveOutOpen(&s_wo, WAVE_MAPPER, &wf, (DWORD_PTR)s_event, 0, CALLBACK_EVENT) != MMSYSERR_NOERROR) {
        fprintf(stderr, "audio: waveOutOpen failed (silent run)\n");
        return s_ok = 0;
    }
    InitializeCriticalSection(&s_lock);
    for (int i = 0; i < NUM_BLOCKS; i++) {
        s_hdr[i].lpData = (LPSTR)s_blk[i];
        s_hdr[i].dwBufferLength = BLOCK_FRAMES * 4;
        waveOutPrepareHeader(s_wo, &s_hdr[i], sizeof(WAVEHDR));
        s_hdr[i].dwFlags |= WHDR_DONE;                     /* available immediately */
    }
    s_run = 1;
    s_thread = CreateThread(NULL, 0, feeder, NULL, 0, NULL);
    SetEvent(s_event);
    fprintf(stderr, "audio: waveOut 44100 Hz stereo open\n");
    return s_ok = 1;
}
#else
static int aaudio_open_stream(void);

static aaudio_data_callback_result_t aaudio_data_cb(
        AAudioStream *stream, void *userData, void *audioData, int32_t numFrames) {
    (void)stream; (void)userData;
    int16_t *out = (int16_t *)audioData;
    AUDIO_LOCK();
    uint64_t max_w = s_play;
    for (int c = 0; c < 8; c++) if (s_chw[c] > max_w) max_w = s_chw[c];
    int avail = (int)(max_w - s_play);
    int take = avail < numFrames ? avail : numFrames;
    for (int i = 0; i < take; i++) {
        uint32_t idx = (uint32_t)((s_play + (uint64_t)i) & RING_MASK);
        s_aa_last_l = out[i * 2 + 0] = clamp16(s_accL[idx]);
        s_aa_last_r = out[i * 2 + 1] = clamp16(s_accR[idx]);
        s_accL[idx] = s_accR[idx] = 0;
    }
    for (int i = take; i < numFrames; i++) {
        s_aa_last_l = (int16_t)((s_aa_last_l * 63) >> 6);
        s_aa_last_r = (int16_t)((s_aa_last_r * 63) >> 6);
        out[i * 2 + 0] = s_aa_last_l;
        out[i * 2 + 1] = s_aa_last_r;
    }
    s_play += (uint64_t)take;
    AUDIO_UNLOCK();
    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

static void *aaudio_reopen_thread(void *arg) {
    (void)arg;
    if (s_aa_stream) {
        AAudioStream_requestStop(s_aa_stream);
        AAudioStream_close(s_aa_stream);
        s_aa_stream = NULL;
    }
    aaudio_open_stream();
    s_aa_reopening = 0;
    return NULL;
}

static void aaudio_err_cb(AAudioStream *stream, void *userData, aaudio_result_t error) {
    (void)stream; (void)userData;
    if (error == AAUDIO_ERROR_DISCONNECTED && !__atomic_exchange_n(&s_aa_reopening, 1, __ATOMIC_SEQ_CST)) {
        pthread_t th;
        if (pthread_create(&th, NULL, aaudio_reopen_thread, NULL) == 0) pthread_detach(th);
        else s_aa_reopening = 0;
    }
}

static int aaudio_open_stream(void) {
    AAudioStreamBuilder *b = NULL;
    if (AAudio_createStreamBuilder(&b) != AAUDIO_OK || !b) return 0;
    AAudioStreamBuilder_setDirection(b, AAUDIO_DIRECTION_OUTPUT);
    AAudioStreamBuilder_setSampleRate(b, 44100);
    AAudioStreamBuilder_setChannelCount(b, 2);
    AAudioStreamBuilder_setFormat(b, AAUDIO_FORMAT_PCM_I16);
    AAudioStreamBuilder_setSharingMode(b, AAUDIO_SHARING_MODE_SHARED);
    AAudioStreamBuilder_setPerformanceMode(b, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    AAudioStreamBuilder_setDataCallback(b, aaudio_data_cb, NULL);
    AAudioStreamBuilder_setErrorCallback(b, aaudio_err_cb, NULL);
    aaudio_result_t r = AAudioStreamBuilder_openStream(b, &s_aa_stream);
    AAudioStreamBuilder_delete(b);
    if (r != AAUDIO_OK || !s_aa_stream) {
        fprintf(stderr, "audio: AAudio openStream failed (%d)\n", (int)r);
        s_aa_stream = NULL;
        return 0;
    }
    int32_t burst = AAudioStream_getFramesPerBurst(s_aa_stream);
    if (burst > 0) AAudioStream_setBufferSizeInFrames(s_aa_stream, burst * 4);
    r = AAudioStream_requestStart(s_aa_stream);
    if (r != AAUDIO_OK) {
        fprintf(stderr, "audio: AAudio requestStart failed (%d)\n", (int)r);
        AAudioStream_close(s_aa_stream);
        s_aa_stream = NULL;
        return 0;
    }
    fprintf(stderr, "audio: pure AAudio %d Hz stereo open (burst=%d, buf=%d)\n",
            AAudioStream_getSampleRate(s_aa_stream),
            burst,
            AAudioStream_getBufferSizeInFrames(s_aa_stream));
    return 1;
}

int sr_audio_init(void) {
    if (s_inited) return s_ok;
    s_inited = 1;
    if (getenv("SR_NOAUDIO")) return s_ok = 0;
    return s_ok = aaudio_open_stream();
}
#endif

/* Mix nframes of interleaved stereo s16 into the channel's slice of the ring.
 * volL/volR are 0..0x8000 (PSP panned-output volumes). */
unsigned long g_audio_frames[8];         /* frames submitted per channel (SR_SCHEDSTAT) */

static volatile uint64_t s_max_ahead = 4096;
void sr_audio_set_max_ahead(int frames) {
    if (frames < 2048) frames = 2048;
    if (frames > RING_FRAMES) frames = RING_FRAMES;
    s_max_ahead = (uint64_t)frames;
}

void sr_audio_push(int ch, const int16_t *lr, int nframes, int volL, int volR) {
    if (!sr_audio_init() || nframes <= 0) return;
    if (ch < 0) ch = 0;
    ch &= 7;
    g_audio_frames[ch] += (unsigned long)nframes;
    /* SR_WAVDUMP=1: append every submitted buffer (volume applied) to audio_ch<N>.raw
     * (s16 stereo 44.1 kHz) -- what the game produced, independent of host playback timing. */
    {
        static int dump = -1; static FILE *f[8];
        if (dump < 0) dump = getenv("SR_WAVDUMP") ? 1 : 0;
        if (dump) {
            if (!f[ch]) { char p[32]; snprintf(p, sizeof p, "audio_ch%d.raw", ch); f[ch] = fopen(p, "wb"); }
            if (f[ch]) {
                for (int i = 0; i < nframes; i++) {
                    int16_t o[2] = { (int16_t)(((int32_t)lr[i * 2] * volL) >> 15),
                                     (int16_t)(((int32_t)lr[i * 2 + 1] * volR) >> 15) };
                    fwrite(o, sizeof o, 1, f[ch]);
                }
                fflush(f[ch]);
            }
        }
    }
    AUDIO_LOCK();
    uint64_t w = s_chw[ch];
    if (w < s_play) w = s_play;                            /* channel fell behind: snap to now */
#ifdef __ANDROID__
    {
        const uint64_t max_ahead = s_max_ahead;
        if (w > s_play + max_ahead) {
            uint64_t snap = s_play + 2048;
            for (uint64_t k = snap; k < w; k++) {
                uint32_t idx = (uint32_t)(k & RING_MASK);
                s_accL[idx] = s_accR[idx] = 0;
            }
            w = snap;
        }
    }
#endif
    if (w + (uint64_t)nframes > s_play + RING_FRAMES)      /* too far ahead: clamp (drop tail) */
        nframes = (int)(s_play + RING_FRAMES - w);
    for (int i = 0; i < nframes; i++) {
        uint32_t idx = (uint32_t)((w + (uint64_t)i) & RING_MASK);
        s_accL[idx] += ((int32_t)lr[i * 2 + 0] * volL) >> 15;
        s_accR[idx] += ((int32_t)lr[i * 2 + 1] * volR) >> 15;
    }
    s_chw[ch] = w + (uint64_t)(nframes > 0 ? nframes : 0);
    AUDIO_UNLOCK();
}

/* The feeder hands waveOut BLOCK_FRAMES at a time, so a channel must stay at least that far
 * ahead of the playhead or the block is taken half-written. */
int sr_audio_block_frames(void) {
#ifdef __ANDROID__
    return 1536;                          /* ~34.8 ms lead cushion ahead of AAudio bursts */
#else
    return BLOCK_FRAMES;
#endif
}

/* Frames this channel has queued ahead of the playhead (-1: no host audio). The blocking
 * output calls pace against this, like real hardware, so drift self-corrects instead of
 * accumulating (an open-loop sleep of the buffer duration always runs slightly slow). */
int sr_audio_queued(int ch) {
    if (!s_ok) return -1;
    ch &= 7;
    AUDIO_LOCK();
    uint64_t w = s_chw[ch], p = s_play;
    AUDIO_UNLOCK();
    return w > p ? (int)(w - p) : 0;
}
