/* H.264 video decode for sceMpeg (PSMF movies) via Windows Media Foundation.
 *
 * The game feeds 2048-byte MPEG-PS packets into the mpeg ring buffer; mpeg.c hands those
 * bytes here. We demux the program stream (video PES packets, stream id 0xE0-0xEF) into an
 * H.264 annex-B elementary stream and run it through the OS H.264 decoder MFT
 * (msmpeg2vdec.dll) in low-latency mode, converting each NV12 frame into the guest video
 * buffer in the PSP pixel format the game asked for.
 *
 * Pull model: sr_h264_feed() only demuxes (cheap); sr_h264_frame() pushes ES chunks into the
 * MFT until it yields one frame (or runs out of data). This keeps the MFT's input queue from
 * overflowing and bounds the work done per sceMpegAvcDecode call.
 *
 * No external dependencies: mfplat.dll / msmpeg2vdec.dll ship with Windows.
 */

#define _CRT_SECURE_NO_WARNINGS
#ifdef _WIN32
#define COBJMACROS
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <mfapi.h>
#include <mftransform.h>
#include <mferror.h>
#elif defined(__ANDROID__)
#include <media/NdkMediaCodec.h>
#include <media/NdkMediaFormat.h>
#include <pthread.h>
#include <time.h>
#if defined(__aarch64__)
#include <arm_neon.h>
#endif
#endif
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>

#ifdef _WIN32
/* GUIDs defined locally (avoids depending on which GUIDs this mingw's mfuuid.a carries). */
static const GUID L_CLSID_CMSH264Decoder = {0x62CE7E72,0x4C71,0x4D20,{0xB1,0x5D,0x45,0x28,0x31,0xA8,0x7D,0x9D}};
static const GUID L_IID_IMFTransform    = {0xbf94c121,0x5b05,0x4e6f,{0x80,0x00,0xba,0x59,0x89,0x61,0x41,0x4d}};
static const GUID L_MFMediaType_Video   = {0x73646976,0x0000,0x0010,{0x80,0x00,0x00,0xAA,0x00,0x38,0x9B,0x71}};
static const GUID L_MFVideoFormat_H264  = {0x34363248,0x0000,0x0010,{0x80,0x00,0x00,0xAA,0x00,0x38,0x9B,0x71}};
static const GUID L_MFVideoFormat_NV12  = {0x3231564E,0x0000,0x0010,{0x80,0x00,0x00,0xAA,0x00,0x38,0x9B,0x71}};
static const GUID L_MF_MT_MAJOR_TYPE    = {0x48eba18e,0xf8c9,0x4687,{0xbf,0x11,0x0a,0x74,0xc9,0xf9,0x6a,0x8f}};
static const GUID L_MF_MT_SUBTYPE       = {0xf7e34c9a,0x42e8,0x4714,{0xb7,0x4b,0xcb,0x29,0xd7,0x2c,0x35,0xe5}};
static const GUID L_MF_MT_FRAME_SIZE    = {0x1652c33d,0xd6b2,0x4012,{0xb8,0x34,0x72,0x03,0x08,0x49,0xa3,0x7d}};
static const GUID L_MF_MT_DEFAULT_STRIDE= {0x644b4e48,0x1e02,0x4516,{0xb0,0xeb,0xc0,0x1c,0xa9,0xd4,0x9a,0xc6}};
static const GUID L_MF_LOW_LATENCY      = {0x9c27891a,0xed7a,0x40e1,{0x88,0xe8,0xb2,0x27,0x27,0xa0,0x24,0xee}};

#ifndef MF_E_TRANSFORM_NEED_MORE_INPUT
#define MF_E_TRANSFORM_NEED_MORE_INPUT ((HRESULT)0xC00D6D72L)
#endif
#ifndef MF_E_TRANSFORM_STREAM_CHANGE
#define MF_E_TRANSFORM_STREAM_CHANGE ((HRESULT)0xC00D6D61L)
#endif
#ifndef MF_E_NOTACCEPTING
#define MF_E_NOTACCEPTING ((HRESULT)0xC00D36B5L)
#endif
#endif /* _WIN32 */

#ifdef __ANDROID__
#define MC_OUT_SLOTS 6
typedef struct {
    uint8_t *yuv;
    uint32_t cap;
    uint32_t len;
    int w, h, stride, sliceH, colorFmt;
} MCOutSlot;
#endif

typedef struct {
    int used;
#ifdef _WIN32
    IMFTransform *xf;
    LONGLONG nextTime;             /* synthetic input timestamp (100 ns) */
#elif defined(__ANDROID__)
    AMediaCodec *mc;
    int64_t nextTimeUs;
    int colorFmt;
    int sliceH;
    uint8_t *lastFrame;
    uint32_t lastFrameBytes;
    pthread_t th;
    int thStarted;
    pthread_mutex_t mu;
    pthread_cond_t cond_in;
    pthread_cond_t cond_out;
    int stop;
    int eos;
    int outEos;
    int firstOut;
    int inFlight;
    MCOutSlot slots[MC_OUT_SLOTS];
    int qHead, qTail, qCount;
#endif
    int outW, outH, outStride;
    int failed;
    int drained;
    /* MPEG-PS input accumulation (unparsed tail) */
    uint8_t *ps; uint32_t psLen, psCap, psPos;
    /* demuxed H.264 ES byte fifo; esCur = first byte not yet handed to the decoder */
    uint8_t *es; uint32_t esLen, esCap, esCur;
} Dec;

#define MAX_DEC 4
static Dec s_dec[MAX_DEC];
static int s_mf_state = 0;         /* 0 = not tried, 1 = up, -1 = failed */

static int mflog(void) { static int v = -1; if (v < 0) v = getenv("SR_MPEGLOG") ? 1 : 0; return v; }

/* ---- buffers -------------------------------------------------------------------------- */
static int buf_reserve(uint8_t **d, uint32_t *cap, uint32_t need) {
    if (need <= *cap) return 1;
    uint32_t n = *cap ? *cap : 65536;
    while (n < need) n *= 2;
    uint8_t *p = (uint8_t *)realloc(*d, n);
    if (!p) return 0;
    *d = p; *cap = n;
    return 1;
}

static void es_append(Dec *d, const uint8_t *p, uint32_t n, int64_t pts) {
    (void)pts;                      /* pictures are delimited by AUD NALs, not PTS (feed_one) */
    if (!buf_reserve(&d->es, &d->esCap, d->esLen + n)) return;
    memcpy(d->es + d->esLen, p, n);
    d->esLen += n;
}

/* Drop consumed bytes so the fifo stays small (the ring is ~1.2MB; the game feeds as we drain). */
static void es_compact(Dec *d) {
    if (d->esCur == d->esLen) { d->esLen = d->esCur = 0; return; }
    if (d->esCur < (1u << 20)) return;
    memmove(d->es, d->es + d->esCur, d->esLen - d->esCur);
    d->esLen -= d->esCur;
    d->esCur = 0;
}

/* ---- MPEG program-stream demux --------------------------------------------------------- */
/* Parse one syntactic element at psPos. Returns 1 if it consumed something, 0 if it needs
 * more bytes. Video PES payloads (stream id 0xE0-0xEF) are appended to the ES fifo with the
 * PES PTS (90 kHz) when present, -1 otherwise. */
static int ps_parse_one(Dec *d) {
    const uint8_t *p = d->ps + d->psPos;
    uint32_t n = d->psLen - d->psPos;
    if (n < 4) return 0;
    /* resync to a start code */
    if (!(p[0] == 0 && p[1] == 0 && p[2] == 1)) {
        uint32_t i = 1;
        while (i + 3 <= n && !(p[i] == 0 && p[i+1] == 0 && p[i+2] == 1)) i++;
        d->psPos += i;
        return (d->psLen - d->psPos) >= 4;
    }
    uint8_t code = p[3];
    if (code == 0xBA) {                          /* pack header: 14 bytes + stuffing */
        if (n < 14) return 0;
        uint32_t skip = 14 + (p[13] & 7);
        if (n < skip) return 0;
        d->psPos += skip;
        return 1;
    }
    if (code == 0xB9) { d->psPos += 4; return 1; }   /* program end */
    if (code < 0xBB) { d->psPos += 4; return 1; }    /* unexpected: skip the start code */
    if (n < 6) return 0;
    uint32_t len = ((uint32_t)p[4] << 8) | p[5];
    uint32_t tot = 6 + len;
    if (n < tot) return 0;
    if (code >= 0xE0 && code <= 0xEF && len >= 3 && (p[6] & 0xC0) == 0x80) {
        uint32_t hdr = 9 + p[8];
        int64_t pts = -1;
        if ((p[7] & 0x80) && p[8] >= 5 && len >= 8) {
            pts = ((int64_t)(p[9]  >> 1 & 7) << 30) |
                  ((int64_t) p[10]          << 22) |
                  ((int64_t)(p[11] >> 1)    << 15) |
                  ((int64_t) p[12]          << 7)  |
                  ((int64_t)(p[13] >> 1));
        }
        if (tot > hdr) es_append(d, p + hdr, tot - hdr, pts);
    }
    d->psPos += tot;
    return 1;
}

/* ---- Media Foundation ------------------------------------------------------------------- */
#ifdef _WIN32
static int mf_up(void) {
    if (s_mf_state) return s_mf_state > 0;
    s_mf_state = -1;
    HRESULT hr = CoInitializeEx(NULL, COINIT_MULTITHREADED);
    if (FAILED(hr) && hr != RPC_E_CHANGED_MODE) return 0;
    if (FAILED(MFStartup(0x00020070 /* MF_VERSION */, MFSTARTUP_LITE))) return 0;
    s_mf_state = 1;
    return 1;
}

static int negotiate_output(Dec *d) {
    for (DWORD i = 0; ; i++) {
        IMFMediaType *t = NULL;
        if (FAILED(IMFTransform_GetOutputAvailableType(d->xf, 0, i, &t)) || !t) break;
        GUID sub;
        if (SUCCEEDED(IMFMediaType_GetGUID(t, &L_MF_MT_SUBTYPE, &sub)) &&
            IsEqualGUID(&sub, &L_MFVideoFormat_NV12) &&
            SUCCEEDED(IMFTransform_SetOutputType(d->xf, 0, t, 0))) {
            UINT64 fs = 0;
            if (SUCCEEDED(IMFMediaType_GetUINT64(t, &L_MF_MT_FRAME_SIZE, &fs))) {
                d->outW = (int)(fs >> 32);
                d->outH = (int)(UINT32)fs;
            }
            UINT32 st = 0;
            d->outStride = (SUCCEEDED(IMFMediaType_GetUINT32(t, &L_MF_MT_DEFAULT_STRIDE, &st)) && (int)st > 0)
                           ? (int)st : d->outW;
            IMFMediaType_Release(t);
            if (mflog()) fprintf(stderr, "h264: output %dx%d stride %d\n", d->outW, d->outH, d->outStride);
            return 1;
        }
        IMFMediaType_Release(t);
    }
    return 0;
}

static int dec_open(Dec *d) {
    if (!mf_up()) return 0;
    HRESULT hr = CoCreateInstance(&L_CLSID_CMSH264Decoder, NULL, CLSCTX_INPROC_SERVER,
                                  &L_IID_IMFTransform, (void **)&d->xf);
    if (FAILED(hr) || !d->xf) {
        fprintf(stderr, "h264: H.264 decoder MFT unavailable (hr=0x%08lx)\n", (unsigned long)hr);
        return 0;
    }
    IMFAttributes *at = NULL;
    if (SUCCEEDED(IMFTransform_GetAttributes(d->xf, &at)) && at) {
        IMFAttributes_SetUINT32(at, &L_MF_LOW_LATENCY, 1);
        IMFAttributes_Release(at);
    }
    IMFMediaType *mt = NULL;
    if (FAILED(MFCreateMediaType(&mt))) return 0;
    IMFMediaType_SetGUID(mt, &L_MF_MT_MAJOR_TYPE, &L_MFMediaType_Video);
    IMFMediaType_SetGUID(mt, &L_MF_MT_SUBTYPE, &L_MFVideoFormat_H264);
    hr = IMFTransform_SetInputType(d->xf, 0, mt, 0);
    IMFMediaType_Release(mt);
    if (FAILED(hr)) {
        fprintf(stderr, "h264: SetInputType(H264) failed (hr=0x%08lx)\n", (unsigned long)hr);
        return 0;
    }
    negotiate_output(d);   /* may only succeed after the first stream change; that's fine */
    IMFTransform_ProcessMessage(d->xf, MFT_MESSAGE_NOTIFY_BEGIN_STREAMING, 0);
    IMFTransform_ProcessMessage(d->xf, MFT_MESSAGE_NOTIFY_START_OF_STREAM, 0);
    return 1;
}

/* Push the next demuxed ES chunk into the MFT. Returns 1 on progress, 0 when the MFT is not
 * accepting (caller should drain output first), -1 on hard failure. */
/* The decoder runs in low-latency mode, which treats every input sample as exactly one picture.
 * A picture spans several PES packets, and these movies carry a PTS only about every 16th
 * picture, so neither PES nor PTS boundaries delimit pictures (grouping up to the next PTS
 * handed the MFT ~16 pictures per sample: block corruption and smears in the movies). Every
 * access unit starts with an access-unit delimiter NAL (type 9), so split there.
 * Returns the offset of the next delimiter's start code at or after `from`, or esLen. */
static uint32_t next_aud(Dec *d, uint32_t from) {
    const uint8_t *e = d->es;
    for (uint32_t i = from; i + 3 < d->esLen; i++) {
        if (e[i + 2] > 1) { i += 2; continue; }           /* quick skip: can't be 00 00 01 */
        if (e[i] == 0 && e[i + 1] == 0 && e[i + 2] == 1 && (e[i + 3] & 0x1F) == 9)
            return (i > from && e[i - 1] == 0) ? i - 1 : i;  /* keep a 4-byte start code whole */
    }
    return d->esLen;
}
static int s_feed_eos = 0;   /* set by sr_h264_frame for the current call */

static int feed_one(Dec *d) {
    if (d->esCur >= d->esLen) return 2;
    uint32_t end = next_aud(d, d->esCur + 4);
    if (end >= d->esLen && !s_feed_eos) return 2;  /* picture incomplete: wait for more feed */
    uint32_t len = end - d->esCur;
    IMFSample *smp = NULL; IMFMediaBuffer *mb = NULL;
    BYTE *base = NULL;
    int ret = -1;
    if (FAILED(MFCreateMemoryBuffer(len, &mb))) return -1;
    if (SUCCEEDED(IMFMediaBuffer_Lock(mb, &base, NULL, NULL))) {
        memcpy(base, d->es + d->esCur, len);
        IMFMediaBuffer_Unlock(mb);
        IMFMediaBuffer_SetCurrentLength(mb, len);
        if (SUCCEEDED(MFCreateSample(&smp))) {
            IMFSample_AddBuffer(smp, mb);
            /* The MFT wants increasing timestamps but we never read them back (frame pacing is
             * the game's): one 29.97 Hz frame per sample, in 100 ns units. */
            IMFSample_SetSampleTime(smp, d->nextTime);
            IMFSample_SetSampleDuration(smp, 333667);
            HRESULT hr = IMFTransform_ProcessInput(d->xf, 0, smp, 0);
            if (SUCCEEDED(hr)) { d->esCur = end; d->nextTime += 333667; es_compact(d); ret = 1; }
            else ret = (hr == MF_E_NOTACCEPTING) ? 0 : -1;
            IMFSample_Release(smp);
        }
    }
    IMFMediaBuffer_Release(mb);
    return ret;
}

static uint8_t clamp8(int v) { return v < 0 ? 0 : v > 255 ? 255 : (uint8_t)v; }

/* NV12 -> PSP pixel format (BT.601 limited range). dst is the guest video buffer (host ptr),
 * frameWidth its stride in pixels; PSP movie buffers are 272 rows tall. */
static void convert_frame(Dec *d, const uint8_t *src, uint32_t srcLen,
                          uint8_t *dst, int frameWidth, int pixelMode) {
    int stride = d->outStride > 0 ? d->outStride : d->outW;
    int w = d->outW, h = d->outH;
    if ((uint32_t)(stride * h + stride * h / 2) > srcLen) return;   /* malformed */
    if (w > frameWidth) w = frameWidth;
    if (h > 272) h = 272;
    const uint8_t *yp = src, *uvp = src + (size_t)stride * d->outH;
    for (int y = 0; y < h; y++) {
        const uint8_t *yr = yp + (size_t)y * stride;
        const uint8_t *uv = uvp + (size_t)(y / 2) * stride;
        uint8_t *o8 = dst + (size_t)y * frameWidth * 4;
        uint16_t *o16 = (uint16_t *)(dst + (size_t)y * frameWidth * 2);
        for (int x = 0; x < w; x++) {
            int c = yr[x] - 16, dd = uv[x & ~1] - 128, e = uv[(x & ~1) + 1] - 128;
            int r = clamp8((298 * c + 409 * e + 128) >> 8);
            int g = clamp8((298 * c - 100 * dd - 208 * e + 128) >> 8);
            int b = clamp8((298 * c + 516 * dd + 128) >> 8);
            switch (pixelMode) {
                case 0:  /* 5650 */
                    o16[x] = (uint16_t)((r >> 3) | ((g >> 2) << 5) | ((b >> 3) << 11));
                    break;
                case 1:  /* 5551 */
                    o16[x] = (uint16_t)((r >> 3) | ((g >> 3) << 5) | ((b >> 3) << 10) | 0x8000);
                    break;
                case 2:  /* 4444 */
                    o16[x] = (uint16_t)((r >> 4) | ((g >> 4) << 4) | ((b >> 4) << 8) | 0xF000);
                    break;
                default: /* 8888: R,G,B,A byte order in guest memory */
                    o8[x * 4 + 0] = (uint8_t)r;
                    o8[x * 4 + 1] = (uint8_t)g;
                    o8[x * 4 + 2] = (uint8_t)b;
                    o8[x * 4 + 3] = 0xFF;
                    break;
            }
        }
    }
}

/* Try to pull one decoded frame. 1 = frame written to dst, 0 = need more input, -1 = failure. */
static int pump_out(Dec *d, uint8_t *dst, int frameWidth, int pixelMode) {
    for (;;) {
        MFT_OUTPUT_STREAM_INFO si;
        memset(&si, 0, sizeof(si));
        if (FAILED(IMFTransform_GetOutputStreamInfo(d->xf, 0, &si))) return -1;
        DWORD cb = si.cbSize ? si.cbSize
                             : (DWORD)((d->outW > 0 ? d->outW : 512) * (d->outH > 0 ? d->outH : 288) * 3 / 2 + 4096);
        IMFSample *smp = NULL; IMFMediaBuffer *mb = NULL;
        if (FAILED(MFCreateSample(&smp))) return -1;
        if (FAILED(MFCreateMemoryBuffer(cb, &mb))) { IMFSample_Release(smp); return -1; }
        IMFSample_AddBuffer(smp, mb);
        MFT_OUTPUT_DATA_BUFFER ob;
        memset(&ob, 0, sizeof(ob));
        ob.pSample = smp;
        DWORD status = 0;
        HRESULT hr = IMFTransform_ProcessOutput(d->xf, 0, 1, &ob, &status);
        if (ob.pEvents) IUnknown_Release((IUnknown *)ob.pEvents);
        if (hr == MF_E_TRANSFORM_STREAM_CHANGE) {
            IMFMediaBuffer_Release(mb); IMFSample_Release(smp);
            if (!negotiate_output(d)) return -1;
            continue;                       /* output size may have changed: retry */
        }
        if (hr == MF_E_TRANSFORM_NEED_MORE_INPUT) {
            IMFMediaBuffer_Release(mb); IMFSample_Release(smp);
            return 0;
        }
        if (FAILED(hr)) {
            IMFMediaBuffer_Release(mb); IMFSample_Release(smp);
            if (mflog()) fprintf(stderr, "h264: ProcessOutput hr=0x%08lx\n", (unsigned long)hr);
            return -1;
        }
        if (dst) {
            IMFMediaBuffer *cbuf = NULL;
            if (SUCCEEDED(IMFSample_ConvertToContiguousBuffer(smp, &cbuf)) && cbuf) {
                BYTE *base = NULL; DWORD cur = 0;
                if (SUCCEEDED(IMFMediaBuffer_Lock(cbuf, &base, NULL, &cur))) {
                    convert_frame(d, base, cur, dst, frameWidth, pixelMode);
                    IMFMediaBuffer_Unlock(cbuf);
                }
                IMFMediaBuffer_Release(cbuf);
            }
        }
        IMFMediaBuffer_Release(mb); IMFSample_Release(smp);
        return 1;
    }
}

#endif /* _WIN32 */

#ifdef __ANDROID__
static uint32_t next_aud(Dec *d, uint32_t from) {
    const uint8_t *e = d->es;
    for (uint32_t i = from; i + 3 < d->esLen; i++) {
        if (e[i + 2] > 1) { i += 2; continue; }
        if (e[i] == 0 && e[i + 1] == 0 && e[i + 2] == 1 && (e[i + 3] & 0x1F) == 9)
            return (i > from && e[i - 1] == 0) ? i - 1 : i;
    }
    return d->esLen;
}
static uint8_t clamp8(int v) { return v < 0 ? 0 : v > 255 ? 255 : (uint8_t)v; }

static void convert_frame_android(const MCOutSlot *s, uint8_t *dst, int frameWidth, int pixelMode) {
    const uint8_t *src = s->yuv;
    uint32_t srcLen = s->len;
    int stride = s->stride > 0 ? s->stride : s->w;
    int sh = s->sliceH > 0 ? s->sliceH : s->h;
    int w = s->w, h = s->h;
    if (!src || w <= 0 || h <= 0) return;
    int planar = (s->colorFmt == 19); /* COLOR_FormatYUV420Planar */
    size_t y_sz = (size_t)stride * sh;
    if (y_sz + y_sz / 2 > srcLen) return;
    if (w > frameWidth) w = frameWidth;
    if (h > 272) h = 272;
    const uint8_t *yp = src;
    const uint8_t *up = src + y_sz;
    const uint8_t *vp = planar ? (up + y_sz / 4) : (up + 1);
    int uv_stride = planar ? (stride / 2) : stride;
    int uv_step = planar ? 1 : 2;
    for (int y = 0; y < h; y++) {
        const uint8_t *yr = yp + (size_t)y * stride;
        const uint8_t *ur = up + (size_t)(y / 2) * uv_stride;
        const uint8_t *vr = vp + (size_t)(y / 2) * uv_stride;
        uint8_t *o8 = dst + (size_t)y * frameWidth * 4;
        uint16_t *o16 = (uint16_t *)(dst + (size_t)y * frameWidth * 2);
        if (pixelMode == 3) {
#if defined(__aarch64__)
            int x = 0;
            if (!planar) {
                const int16x8_t v_16  = vdupq_n_s16(16);
                const int16x8_t v_128 = vdupq_n_s16(128);
                const int16x4_t k_298 = vdup_n_s16(298);
                const int16x4_t k_409 = vdup_n_s16(409);
                const int16x4_t k_100 = vdup_n_s16(-100);
                const int16x4_t k_208 = vdup_n_s16(-208);
                const int16x4_t k_516 = vdup_n_s16(516);
                const int32x4_t k_rnd = vdupq_n_s32(128);
                const uint8x16_t v_ff = vdupq_n_u8(0xFF);
                for (; x + 15 < w; x += 16) {
                    uint8x16_t y16 = vld1q_u8(yr + x);
                    uint8x8x2_t uv8 = vld2_u8(ur + x); /* NV12: interleaved U, V */
                    uint8x8x2_t uz = vzip_u8(uv8.val[0], uv8.val[0]);
                    uint8x8x2_t vz = vzip_u8(uv8.val[1], uv8.val[1]);
                    uint8x16_t u16 = vcombine_u8(uz.val[0], uz.val[1]);
                    uint8x16_t v16 = vcombine_u8(vz.val[0], vz.val[1]);

                    int16x8_t ys[2] = {
                        vsubq_s16(vreinterpretq_s16_u16(vmovl_u8(vget_low_u8(y16))), v_16),
                        vsubq_s16(vreinterpretq_s16_u16(vmovl_u8(vget_high_u8(y16))), v_16)
                    };
                    int16x8_t us[2] = {
                        vsubq_s16(vreinterpretq_s16_u16(vmovl_u8(vget_low_u8(u16))), v_128),
                        vsubq_s16(vreinterpretq_s16_u16(vmovl_u8(vget_high_u8(u16))), v_128)
                    };
                    int16x8_t vs[2] = {
                        vsubq_s16(vreinterpretq_s16_u16(vmovl_u8(vget_low_u8(v16))), v_128),
                        vsubq_s16(vreinterpretq_s16_u16(vmovl_u8(vget_high_u8(v16))), v_128)
                    };

                    uint8x8_t r8[2], g8[2], b8[2];
                    for (int h2 = 0; h2 < 2; h2++) {
                        int16x4_t yl = vget_low_s16(ys[h2]), yh = vget_high_s16(ys[h2]);
                        int16x4_t ul = vget_low_s16(us[h2]), uh = vget_high_s16(us[h2]);
                        int16x4_t vl = vget_low_s16(vs[h2]), vh = vget_high_s16(vs[h2]);

                        int32x4_t cl = vmlal_s16(k_rnd, yl, k_298), ch = vmlal_s16(k_rnd, yh, k_298);
                        int32x4_t rl = vmlal_s16(cl, vl, k_409), rh = vmlal_s16(ch, vh, k_409);
                        int32x4_t gl = vmlal_s16(vmlal_s16(cl, ul, k_100), vl, k_208);
                        int32x4_t gh = vmlal_s16(vmlal_s16(ch, uh, k_100), vh, k_208);
                        int32x4_t bl = vmlal_s16(cl, ul, k_516), bh = vmlal_s16(ch, uh, k_516);

                        r8[h2] = vqmovun_s16(vcombine_s16(vshrn_n_s32(rl, 8), vshrn_n_s32(rh, 8)));
                        g8[h2] = vqmovun_s16(vcombine_s16(vshrn_n_s32(gl, 8), vshrn_n_s32(gh, 8)));
                        b8[h2] = vqmovun_s16(vcombine_s16(vshrn_n_s32(bl, 8), vshrn_n_s32(bh, 8)));
                    }
                    uint8x16x4_t rgba;
                    rgba.val[0] = vcombine_u8(r8[0], r8[1]);
                    rgba.val[1] = vcombine_u8(g8[0], g8[1]);
                    rgba.val[2] = vcombine_u8(b8[0], b8[1]);
                    rgba.val[3] = v_ff;
                    vst4q_u8(o8 + x * 4, rgba);
                }
            }
            for (; x + 1 < w; x += 2) {
#else
            for (int x = 0; x + 1 < w; x += 2) {
#endif
                int cx = (x >> 1) * uv_step;
                int dd = ur[cx] - 128, e = vr[cx] - 128;
                int cr = 409 * e + 128;
                int cg = -100 * dd - 208 * e + 128;
                int cb = 516 * dd + 128;
                int c0 = (yr[x] - 16) * 298;
                int c1 = (yr[x + 1] - 16) * 298;
                o8[x * 4 + 0] = clamp8((c0 + cr) >> 8);
                o8[x * 4 + 1] = clamp8((c0 + cg) >> 8);
                o8[x * 4 + 2] = clamp8((c0 + cb) >> 8);
                o8[x * 4 + 3] = 0xFF;
                o8[x * 4 + 4] = clamp8((c1 + cr) >> 8);
                o8[x * 4 + 5] = clamp8((c1 + cg) >> 8);
                o8[x * 4 + 6] = clamp8((c1 + cb) >> 8);
                o8[x * 4 + 7] = 0xFF;
            }
        } else {
            for (int x = 0; x < w; x++) {
                int cx = (x >> 1) * uv_step;
                int c = (yr[x] - 16) * 298, dd = ur[cx] - 128, e = vr[cx] - 128;
                int r = clamp8((c + 409 * e + 128) >> 8);
                int g = clamp8((c - 100 * dd - 208 * e + 128) >> 8);
                int b = clamp8((c + 516 * dd + 128) >> 8);
                if (pixelMode == 0)
                    o16[x] = (uint16_t)((r >> 3) | ((g >> 2) << 5) | ((b >> 3) << 11));
                else if (pixelMode == 1)
                    o16[x] = (uint16_t)((r >> 3) | ((g >> 3) << 5) | ((b >> 3) << 10) | 0x8000);
                else
                    o16[x] = (uint16_t)((r >> 4) | ((g >> 4) << 4) | ((b >> 4) << 8) | 0xF000);
            }
        }
    }
}

static void *mc_worker(void *arg) {
    Dec *d = (Dec *)arg;
    pthread_setname_np(pthread_self(), "H264Dec");
    uint8_t *auBuf = NULL;
    uint32_t auCap = 0, auLen = 0;

    for (;;) {
        pthread_mutex_lock(&d->mu);
        while (!d->stop && !d->failed) {
            int can_pop_au = (auLen == 0 && d->esCur < d->esLen);
            if (can_pop_au) {
                uint32_t end = next_aud(d, d->esCur + 4);
                if (end >= d->esLen && !d->eos) can_pop_au = 0;
            }
            int can_send_eos = (auLen == 0 && d->esCur >= d->esLen && d->eos && !d->drained);
            int can_drain_out = (d->qCount < MC_OUT_SLOTS && (d->inFlight > 0 || d->drained) && !d->outEos);
            if (auLen > 0 || can_pop_au || can_send_eos || can_drain_out) break;
            pthread_cond_wait(&d->cond_in, &d->mu);
        }
        if (d->stop || d->failed) {
            pthread_mutex_unlock(&d->mu);
            break;
        }
        if (auLen == 0 && d->esCur < d->esLen) {
            uint32_t end = next_aud(d, d->esCur + 4);
            if (end < d->esLen || d->eos) {
                uint32_t len = end - d->esCur;
                if (len > 0 && buf_reserve(&auBuf, &auCap, len)) {
                    memcpy(auBuf, d->es + d->esCur, len);
                    auLen = len;
                }
                d->esCur = end;
                es_compact(d);
            }
        }
        int want_eos = (auLen == 0 && d->esCur >= d->esLen && d->eos && !d->drained);
        int q_full = (d->qCount >= MC_OUT_SLOTS);
        int in_flight = d->inFlight;
        int drained = d->drained;
        int out_eos = d->outEos;
        pthread_mutex_unlock(&d->mu);

        if (auLen > 0) {
            ssize_t inIdx = AMediaCodec_dequeueInputBuffer(d->mc, 1000);
            if (inIdx >= 0) {
                size_t isz = 0;
                uint8_t *ibuf = AMediaCodec_getInputBuffer(d->mc, (size_t)inIdx, &isz);
                if (ibuf && auLen <= isz) {
                    memcpy(ibuf, auBuf, auLen);
                    AMediaCodec_queueInputBuffer(d->mc, (size_t)inIdx, 0, auLen, (uint64_t)d->nextTimeUs, 0);
                    d->nextTimeUs += 33367;
                    pthread_mutex_lock(&d->mu);
                    d->inFlight++;
                    pthread_mutex_unlock(&d->mu);
                } else {
                    AMediaCodec_queueInputBuffer(d->mc, (size_t)inIdx, 0, 0, 0, 0);
                }
                auLen = 0;
            }
        } else if (want_eos) {
            ssize_t inIdx = AMediaCodec_dequeueInputBuffer(d->mc, 1000);
            if (inIdx >= 0) {
                AMediaCodec_queueInputBuffer(d->mc, (size_t)inIdx, 0, 0, 0, AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM);
                pthread_mutex_lock(&d->mu);
                d->drained = 1;
                pthread_mutex_unlock(&d->mu);
            }
        }

        if (!q_full && (in_flight > 0 || drained || auLen > 0) && !out_eos) {
            AMediaCodecBufferInfo info;
            int64_t wait_us = (auLen > 0 || want_eos) ? 1000 : 4000;
            ssize_t outIdx = AMediaCodec_dequeueOutputBuffer(d->mc, &info, wait_us);
            if (outIdx == AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED) {
                AMediaFormat *of = AMediaCodec_getOutputFormat(d->mc);
                if (of) {
                    int32_t v = 0;
                    if (AMediaFormat_getInt32(of, AMEDIAFORMAT_KEY_WIDTH, &v) && v > 0) d->outW = v;
                    if (AMediaFormat_getInt32(of, AMEDIAFORMAT_KEY_HEIGHT, &v) && v > 0) d->outH = v;
                    if (AMediaFormat_getInt32(of, AMEDIAFORMAT_KEY_STRIDE, &v) && v > 0) d->outStride = v;
                    else d->outStride = d->outW;
                    if (AMediaFormat_getInt32(of, "slice-height", &v) && v > 0) d->sliceH = v;
                    else d->sliceH = d->outH;
                    if (AMediaFormat_getInt32(of, AMEDIAFORMAT_KEY_COLOR_FORMAT, &v)) d->colorFmt = v;
                    AMediaFormat_delete(of);
                }
            } else if (outIdx >= 0) {
                size_t osz = 0;
                uint8_t *obuf = AMediaCodec_getOutputBuffer(d->mc, (size_t)outIdx, &osz);
                if (obuf && info.size > 0) {
                    pthread_mutex_lock(&d->mu);
                    if (d->qCount < MC_OUT_SLOTS) {
                        MCOutSlot *s = &d->slots[d->qTail];
                        if (buf_reserve(&s->yuv, &s->cap, (uint32_t)info.size)) {
                            memcpy(s->yuv, obuf + info.offset, (size_t)info.size);
                            s->len = (uint32_t)info.size;
                            s->w = d->outW;
                            s->h = d->outH;
                            s->stride = d->outStride;
                            s->sliceH = d->sliceH;
                            s->colorFmt = d->colorFmt;
                            d->qTail = (d->qTail + 1) % MC_OUT_SLOTS;
                            d->qCount++;
                        }
                    }
                    if (d->inFlight > 0) d->inFlight--;
                    if (info.flags & AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM) d->outEos = 1;
                    pthread_cond_signal(&d->cond_out);
                    pthread_mutex_unlock(&d->mu);
                } else {
                    pthread_mutex_lock(&d->mu);
                    if (d->inFlight > 0) d->inFlight--;
                    if (info.flags & AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM) d->outEos = 1;
                    pthread_cond_signal(&d->cond_out);
                    pthread_mutex_unlock(&d->mu);
                }
                AMediaCodec_releaseOutputBuffer(d->mc, (size_t)outIdx, false);
            }
        }
    }
    free(auBuf);
    return NULL;
}

static int mc_open(Dec *d) {
    d->mc = AMediaCodec_createDecoderByType("video/avc");
    if (!d->mc) return 0;
    AMediaFormat *fmt = AMediaFormat_new();
    AMediaFormat_setString(fmt, AMEDIAFORMAT_KEY_MIME, "video/avc");
    AMediaFormat_setInt32(fmt, AMEDIAFORMAT_KEY_WIDTH, 480);
    AMediaFormat_setInt32(fmt, AMEDIAFORMAT_KEY_HEIGHT, 272);
    /* Do NOT force low-latency=1: PSP H.264 streams use B-frames and need display-order reordering
     * in AMediaCodec to prevent out-of-order frame rollbacks. */
    media_status_t st = AMediaCodec_configure(d->mc, fmt, NULL, NULL, 0);
    AMediaFormat_delete(fmt);
    if (st != AMEDIA_OK) { AMediaCodec_delete(d->mc); d->mc = NULL; return 0; }
    if (AMediaCodec_start(d->mc) != AMEDIA_OK) { AMediaCodec_delete(d->mc); d->mc = NULL; return 0; }
    d->outW = 480; d->outH = 272; d->outStride = 480; d->sliceH = 272; d->colorFmt = 21;
    pthread_mutex_init(&d->mu, NULL);
    pthread_condattr_t cattr;
    pthread_condattr_init(&cattr);
    pthread_condattr_setclock(&cattr, CLOCK_MONOTONIC);
    pthread_cond_init(&d->cond_in, &cattr);
    pthread_cond_init(&d->cond_out, &cattr);
    pthread_condattr_destroy(&cattr);
    if (pthread_create(&d->th, NULL, mc_worker, d) != 0) {
        pthread_cond_destroy(&d->cond_in);
        pthread_cond_destroy(&d->cond_out);
        pthread_mutex_destroy(&d->mu);
        AMediaCodec_stop(d->mc);
        AMediaCodec_delete(d->mc);
        d->mc = NULL;
        return 0;
    }
    d->thStarted = 1;
    return 1;
}
#endif

/* ================= public API (called from mpeg.c) ================= */

int sr_h264_create(void) {
    if (getenv("SR_NOH264")) return -1;
    for (int i = 0; i < MAX_DEC; i++) if (!s_dec[i].used) {
        Dec *d = &s_dec[i];
        memset(d, 0, sizeof(*d));
        d->used = 1;
#ifdef _WIN32
        if (!dec_open(d)) {
            if (d->xf) { IMFTransform_Release(d->xf); d->xf = NULL; }
            d->used = 0;
            return -1;
        }
#elif defined(__ANDROID__)
        if (!mc_open(d)) { d->used = 0; return -1; }
#endif
        if (mflog()) fprintf(stderr, "h264: decoder %d open\n", i);
        return i;
    }
    return -1;
}

void sr_h264_destroy(int id) {
    if (id < 0 || id >= MAX_DEC || !s_dec[id].used) return;
    Dec *d = &s_dec[id];
#ifdef _WIN32
    if (d->xf) IMFTransform_Release(d->xf);
#elif defined(__ANDROID__)
    if (d->thStarted) {
        pthread_mutex_lock(&d->mu);
        d->stop = 1;
        pthread_cond_broadcast(&d->cond_in);
        pthread_cond_broadcast(&d->cond_out);
        pthread_mutex_unlock(&d->mu);
        pthread_join(d->th, NULL);
        pthread_cond_destroy(&d->cond_in);
        pthread_cond_destroy(&d->cond_out);
        pthread_mutex_destroy(&d->mu);
        d->thStarted = 0;
    }
    for (int i = 0; i < MC_OUT_SLOTS; i++) free(d->slots[i].yuv);
    if (d->mc) { AMediaCodec_stop(d->mc); AMediaCodec_delete(d->mc); }
    free(d->lastFrame);
#endif
    free(d->ps); free(d->es);
    memset(d, 0, sizeof(*d));
}

void sr_h264_feed(int id, const uint8_t *data, uint32_t len) {
    if (id < 0 || id >= MAX_DEC || !s_dec[id].used || !data || !len) return;
    Dec *d = &s_dec[id];
#ifdef __ANDROID__
    if (d->thStarted) pthread_mutex_lock(&d->mu);
#endif
    if (!d->failed) {
        if (buf_reserve(&d->ps, &d->psCap, d->psLen + len)) {
            memcpy(d->ps + d->psLen, data, len);
            d->psLen += len;
            while (ps_parse_one(d)) {}
            if (d->psPos > (1u << 20)) {            /* drop the parsed prefix */
                memmove(d->ps, d->ps + d->psPos, d->psLen - d->psPos);
                d->psLen -= d->psPos;
                d->psPos = 0;
            }
        }
    }
#ifdef __ANDROID__
    if (d->thStarted) {
        pthread_cond_signal(&d->cond_in);
        pthread_mutex_unlock(&d->mu);
    }
#endif
}

/* Bytes fed but not yet handed to the decoder (unparsed PS + queued ES). mpeg.c derives the
 * ringbuffer's packetsAvail from this, as PPSSPP does from its media engine's buffer level. */
uint32_t sr_h264_pending_bytes(int id) {
    if (id < 0 || id >= MAX_DEC || !s_dec[id].used) return 0;
    Dec *d = &s_dec[id];
#ifdef __ANDROID__
    if (d->thStarted) pthread_mutex_lock(&d->mu);
#endif
    uint32_t es = d->esLen - d->esCur;
    /* An unparsed PS tail shorter than one packet can be trailing padding that never forms a
     * complete element; counting it kept packetsAvail at 1 forever at end of stream, so the
     * game never saw the movie end (it froze on the opening's last frame). */
    uint32_t ps = d->psLen - d->psPos;
#ifdef __ANDROID__
    if (d->thStarted) pthread_mutex_unlock(&d->mu);
#endif
    return (ps >= 2048 ? ps : 0) + es;
}

/* Decode the next frame into dst (host pointer to the guest video buffer). eos != 0 once the
 * game has fed the whole movie, so the MFT gets drained for the last buffered frames.
 * Returns 1 if a frame was written, 0 if none is available yet, -1 if decoding failed. */
int sr_h264_frame(int id, int eos, uint8_t *dst, int frameWidth, int pixelMode) {
    if (id < 0 || id >= MAX_DEC || !s_dec[id].used) return -1;
    Dec *d = &s_dec[id];
#ifdef _WIN32
    if (d->failed || !d->xf) return -1;
    s_feed_eos = eos;
    for (int guard = 0; guard < 4096; guard++) {
        int r = pump_out(d, dst, frameWidth, pixelMode);
        if (r > 0) return 1;
        if (r < 0) { d->failed = 1; return -1; }
        if (d->esCur < d->esLen) {
            int f = feed_one(d);
            if (f < 0) { d->failed = 1; return -1; }
            if (f == 0) return 0;           /* MFT full but no output: shouldn't happen */
            if (f == 2) {                   /* next frame not complete yet */
                if (!eos) return 0;
            }
        } else if (eos && !d->drained) {
            d->drained = 1;
            IMFTransform_ProcessMessage(d->xf, MFT_MESSAGE_NOTIFY_END_OF_STREAM, 0);
            IMFTransform_ProcessMessage(d->xf, MFT_MESSAGE_COMMAND_DRAIN, 0);
        } else {
            return 0;                       /* out of compressed data: wait for more feed */
        }
    }
    return 0;
#elif defined(__ANDROID__)
    if (d->failed || !d->mc) return -1;
    const uint32_t fb_bytes = (dst && frameWidth > 0)
        ? (uint32_t)frameWidth * 272u * (pixelMode == 3 ? 4u : 2u) : 0u;
    #define SAVE_LAST() do { \
        if (fb_bytes) { \
            if (d->lastFrameBytes < fb_bytes) { \
                uint8_t *nf = (uint8_t *)realloc(d->lastFrame, fb_bytes); \
                if (nf) { d->lastFrame = nf; d->lastFrameBytes = fb_bytes; } \
            } \
            if (d->lastFrame && d->lastFrameBytes >= fb_bytes) memcpy(d->lastFrame, dst, fb_bytes); \
        } \
    } while (0)
    #define MISS_RET() do { \
        if (fb_bytes && d->lastFrame && d->lastFrameBytes >= fb_bytes) memcpy(dst, d->lastFrame, fb_bytes); \
        return 0; \
    } while (0)

    pthread_mutex_lock(&d->mu);
    if (eos && !d->eos) {
        d->eos = 1;
        pthread_cond_signal(&d->cond_in);
    }
    if (d->qCount == 0 && !d->outEos && !d->failed) {
        int has_work = (d->esLen > d->esCur || d->inFlight > 0 || d->eos);
        if (has_work) {
            long wait_ms = d->firstOut ? 8 : 35;
            struct timespec ts;
            clock_gettime(CLOCK_MONOTONIC, &ts);
            ts.tv_nsec += wait_ms * 1000000L;
            if (ts.tv_nsec >= 1000000000L) {
                ts.tv_sec += ts.tv_nsec / 1000000000L;
                ts.tv_nsec %= 1000000000L;
            }
            while (d->qCount == 0 && !d->outEos && !d->failed) {
                if (pthread_cond_timedwait(&d->cond_out, &d->mu, &ts) != 0) break;
            }
        }
    }
    if (d->qCount > 0) {
        MCOutSlot *s = &d->slots[d->qHead];
        if (dst && s->len > 0) {
            convert_frame_android(s, dst, frameWidth, pixelMode);
        }
        d->qHead = (d->qHead + 1) % MC_OUT_SLOTS;
        d->qCount--;
        d->firstOut = 1;
        pthread_cond_signal(&d->cond_in);
        pthread_mutex_unlock(&d->mu);
        if (dst) SAVE_LAST();
        return 1;
    }
    pthread_mutex_unlock(&d->mu);
    MISS_RET();
    #undef SAVE_LAST
    #undef MISS_RET
#else
    (void)eos; (void)pixelMode;
    if (d->esCur < d->esLen) {
        d->esCur = d->esLen;
        es_compact(d);
        if (dst && frameWidth > 0) memset(dst, 0, (size_t)frameWidth * 272 * 4);
        return 1;
    }
    return 0;
#endif
}
