/* Minimal Win32 compatibility layer for POSIX / Android builds (#ifndef _WIN32). */
#pragma once

#ifndef _WIN32
#include <stdint.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>
#include <dirent.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>
#include <time.h>
#include <errno.h>
#include <limits.h>

#ifndef MAX_PATH
#define MAX_PATH 4096
#endif

typedef uint32_t DWORD;
typedef uint16_t WORD;
typedef int32_t LONG;
typedef int BOOL;
typedef int64_t LONGLONG;
typedef size_t SIZE_T;

static inline LONG InterlockedExchange(volatile LONG *p, LONG v) {
    return __atomic_exchange_n(p, v, __ATOMIC_SEQ_CST);
}
static inline LONG InterlockedIncrement(volatile LONG *p) {
    return __atomic_add_fetch(p, 1, __ATOMIC_SEQ_CST);
}
typedef void *HANDLE;
typedef struct { uint32_t dwLowDateTime, dwHighDateTime; } FILETIME;
typedef struct { WORD wYear, wMonth, wDayOfWeek, wDay, wHour, wMinute, wSecond, wMilliseconds; } SYSTEMTIME;
typedef union { int64_t QuadPart; } LARGE_INTEGER;

#ifndef TRUE
#define TRUE 1
#define FALSE 0
#endif

static inline void timeBeginPeriod(DWORD ms) { (void)ms; }

static inline void GetSystemTimeAsFileTime(FILETIME *ft) {
    struct timespec ts;
    clock_gettime(CLOCK_REALTIME, &ts);
    uint64_t t = ((uint64_t)ts.tv_sec + 11644473600ull) * 10000000ull + (uint64_t)ts.tv_nsec / 100ull;
    ft->dwLowDateTime = (DWORD)(t & 0xFFFFFFFFu);
    ft->dwHighDateTime = (DWORD)(t >> 32);
}

static inline BOOL FileTimeToSystemTime(const FILETIME *ft, SYSTEMTIME *st) {
    uint64_t t = ((uint64_t)ft->dwHighDateTime << 32) | ft->dwLowDateTime;
    uint64_t secs = t / 10000000ull;
    if (secs < 11644473600ull) return FALSE;
    time_t unix_t = (time_t)(secs - 11644473600ull);
    struct tm tm_v;
    if (!gmtime_r(&unix_t, &tm_v)) return FALSE;
    st->wYear = (WORD)(tm_v.tm_year + 1900);
    st->wMonth = (WORD)(tm_v.tm_mon + 1);
    st->wDayOfWeek = (WORD)tm_v.tm_wday;
    st->wDay = (WORD)tm_v.tm_mday;
    st->wHour = (WORD)tm_v.tm_hour;
    st->wMinute = (WORD)tm_v.tm_min;
    st->wSecond = (WORD)tm_v.tm_sec;
    st->wMilliseconds = (WORD)((t % 10000000ull) / 10000ull);
    return TRUE;
}

#ifndef TRUE
#define TRUE 1
#define FALSE 0
#endif

#define INVALID_HANDLE_VALUE ((HANDLE)(intptr_t)-1)
#define INVALID_FILE_ATTRIBUTES ((DWORD)0xFFFFFFFFu)
#define FILE_ATTRIBUTE_DIRECTORY 0x10u
#define FILE_ATTRIBUTE_NORMAL    0x80u
#define MOVEFILE_REPLACE_EXISTING 1
#define MOVEFILE_WRITE_THROUGH    2
#define ERROR_ALREADY_EXISTS EEXIST
#define ERROR_DIR_NOT_EMPTY  ENOTEMPTY

static inline DWORD GetLastError(void) { return (DWORD)errno; }
static inline BOOL RemoveDirectoryA(const char *path) { return rmdir(path) == 0; }
#if defined(__ANDROID__) && __ANDROID_API__ < 29
#ifndef TIME_UTC
#define TIME_UTC 1
#endif
static inline int timespec_get(struct timespec *ts, int base) {
    (void)base;
    return clock_gettime(CLOCK_REALTIME, ts) == 0 ? TIME_UTC : 0;
}
#endif

#define _stricmp  strcasecmp
#define _strnicmp strncasecmp
#define stricmp   strcasecmp
#define strnicmp  strncasecmp
#define _fseeki64 fseeko64
#define _ftelli64 ftello64

static inline void GetLocalTime(SYSTEMTIME *st) {
    time_t t = time(NULL);
    struct tm tm_v;
    localtime_r(&t, &tm_v);
    st->wYear = (WORD)(tm_v.tm_year + 1900);
    st->wMonth = (WORD)(tm_v.tm_mon + 1);
    st->wDayOfWeek = (WORD)tm_v.tm_wday;
    st->wDay = (WORD)tm_v.tm_mday;
    st->wHour = (WORD)tm_v.tm_hour;
    st->wMinute = (WORD)tm_v.tm_min;
    st->wSecond = (WORD)tm_v.tm_sec;
    st->wMilliseconds = 0;
}

static inline BOOL FileTimeToLocalFileTime(const FILETIME *ft, FILETIME *lft) {
    *lft = *ft;
    return TRUE;
}

static inline int CompareFileTime(const FILETIME *a, const FILETIME *b) {
    uint64_t ua = ((uint64_t)a->dwHighDateTime << 32) | a->dwLowDateTime;
    uint64_t ub = ((uint64_t)b->dwHighDateTime << 32) | b->dwLowDateTime;
    return (ua > ub) - (ua < ub);
}

static inline BOOL CopyFileA(const char *src, const char *dst, BOOL fail_if_exists) {
    if (fail_if_exists && access(dst, F_OK) == 0) return FALSE;
    FILE *in = fopen(src, "rb");
    if (!in) return FALSE;
    FILE *out = fopen(dst, "wb");
    if (!out) { fclose(in); return FALSE; }
    char buf[8192];
    size_t n;
    while ((n = fread(buf, 1, sizeof(buf), in)) > 0) {
        if (fwrite(buf, 1, n, out) != n) { fclose(in); fclose(out); return FALSE; }
    }
    fclose(in); fclose(out);
    return TRUE;
}

static inline void Sleep(DWORD ms) { usleep((useconds_t)ms * 1000u); }

static inline uint64_t GetTickCount64(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint64_t)ts.tv_sec * 1000ull + (uint64_t)ts.tv_nsec / 1000000ull;
}

static inline BOOL QueryPerformanceFrequency(LARGE_INTEGER *freq) {
    freq->QuadPart = 1000000000ll;
    return TRUE;
}

static inline BOOL QueryPerformanceCounter(LARGE_INTEGER *cnt) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    cnt->QuadPart = (int64_t)ts.tv_sec * 1000000000ll + (int64_t)ts.tv_nsec;
    return TRUE;
}

static inline BOOL CreateDirectoryA(const char *path, void *sa) {
    (void)sa;
    return mkdir(path, 0755) == 0;
}

static inline BOOL DeleteFileA(const char *path) {
    return unlink(path) == 0;
}

static inline BOOL MoveFileExA(const char *src, const char *dst, DWORD flags) {
    (void)flags;
    return rename(src, dst) == 0;
}

static inline DWORD GetFileAttributesA(const char *path) {
    struct stat st;
    if (stat(path, &st) != 0) return INVALID_FILE_ATTRIBUTES;
    return S_ISDIR(st.st_mode) ? FILE_ATTRIBUTE_DIRECTORY : FILE_ATTRIBUTE_NORMAL;
}

typedef enum { GetFileExInfoStandard = 0 } GET_FILEEX_INFO_LEVELS;
typedef struct {
    DWORD dwFileAttributes;
    FILETIME ftCreationTime, ftLastAccessTime, ftLastWriteTime;
    DWORD nFileSizeHigh, nFileSizeLow;
} WIN32_FILE_ATTRIBUTE_DATA;

static inline BOOL GetFileAttributesExA(const char *path, GET_FILEEX_INFO_LEVELS lvl, void *out) {
    (void)lvl;
    struct stat st;
    if (stat(path, &st) != 0) return FALSE;
    WIN32_FILE_ATTRIBUTE_DATA *d = (WIN32_FILE_ATTRIBUTE_DATA *)out;
    memset(d, 0, sizeof(*d));
    d->dwFileAttributes = S_ISDIR(st.st_mode) ? FILE_ATTRIBUTE_DIRECTORY : FILE_ATTRIBUTE_NORMAL;
    uint64_t sz = (uint64_t)st.st_size;
    d->nFileSizeLow = (DWORD)(sz & 0xFFFFFFFFu);
    d->nFileSizeHigh = (DWORD)(sz >> 32);
    uint64_t ft = ((uint64_t)st.st_mtime + 11644473600ull) * 10000000ull;
    d->ftLastWriteTime.dwLowDateTime = (DWORD)(ft & 0xFFFFFFFFu);
    d->ftLastWriteTime.dwHighDateTime = (DWORD)(ft >> 32);
    return TRUE;
}

typedef struct {
    DWORD dwFileAttributes;
    FILETIME ftCreationTime, ftLastAccessTime, ftLastWriteTime;
    DWORD nFileSizeHigh, nFileSizeLow;
    DWORD dwReserved0, dwReserved1;
    char cFileName[MAX_PATH];
    char cAlternateFileName[14];
} WIN32_FIND_DATAA;

typedef struct {
    DIR *dir;
    char dir_path[MAX_PATH];
    char pat[256];
} PosixFindHandle;

static inline int posix_glob_match(const char *pat, const char *s) {
    if (!pat || !*pat || strcmp(pat, "*") == 0 || strcmp(pat, "*.*") == 0) return 1;
    const char *star = strchr(pat, '*');
    if (!star) return strcmp(pat, s) == 0;
    size_t pre = (size_t)(star - pat);
    if (pre && strncmp(pat, s, pre) != 0) return 0;
    const char *suf = star + 1;
    size_t slen = strlen(s), suflen = strlen(suf);
    if (slen < pre + suflen) return 0;
    return strcmp(s + slen - suflen, suf) == 0;
}

static inline void posix_fill_find(PosixFindHandle *fh, const struct dirent *de, WIN32_FIND_DATAA *fd) {
    memset(fd, 0, sizeof(*fd));
    snprintf(fd->cFileName, sizeof(fd->cFileName), "%s", de->d_name);
    char full[MAX_PATH];
    snprintf(full, sizeof(full), "%s/%s", fh->dir_path, de->d_name);
    struct stat st;
    if (stat(full, &st) == 0) {
        fd->dwFileAttributes = S_ISDIR(st.st_mode) ? FILE_ATTRIBUTE_DIRECTORY : FILE_ATTRIBUTE_NORMAL;
        uint64_t sz = (uint64_t)st.st_size;
        fd->nFileSizeLow = (DWORD)(sz & 0xFFFFFFFFu);
        fd->nFileSizeHigh = (DWORD)(sz >> 32);
    } else {
        fd->dwFileAttributes = FILE_ATTRIBUTE_NORMAL;
    }
}

static inline BOOL FindNextFileA(HANDLE h, WIN32_FIND_DATAA *fd) {
    if (h == INVALID_HANDLE_VALUE || !h) return FALSE;
    PosixFindHandle *fh = (PosixFindHandle *)h;
    struct dirent *de;
    while ((de = readdir(fh->dir)) != NULL) {
        if (posix_glob_match(fh->pat, de->d_name)) {
            posix_fill_find(fh, de, fd);
            return TRUE;
        }
    }
    return FALSE;
}

static inline HANDLE FindFirstFileA(const char *pattern, WIN32_FIND_DATAA *fd) {
    char dir[MAX_PATH];
    const char *slash = strrchr(pattern, '/');
    const char *bslash = strrchr(pattern, '\\');
    const char *sep = slash > bslash ? slash : bslash;
    const char *pat = "*";
    if (sep) {
        size_t len = (size_t)(sep - pattern);
        if (len >= sizeof(dir)) len = sizeof(dir) - 1;
        memcpy(dir, pattern, len);
        dir[len] = 0;
        if (!dir[0]) strcpy(dir, "/");
        pat = sep + 1;
    } else {
        strcpy(dir, ".");
        pat = pattern;
    }
    for (char *p = dir; *p; p++) if (*p == '\\') *p = '/';
    DIR *d = opendir(dir);
    if (!d) return INVALID_HANDLE_VALUE;
    PosixFindHandle *fh = (PosixFindHandle *)calloc(1, sizeof(PosixFindHandle));
    if (!fh) { closedir(d); return INVALID_HANDLE_VALUE; }
    fh->dir = d;
    snprintf(fh->dir_path, sizeof(fh->dir_path), "%s", dir);
    snprintf(fh->pat, sizeof(fh->pat), "%s", pat);
    if (!FindNextFileA((HANDLE)fh, fd)) {
        closedir(d);
        free(fh);
        return INVALID_HANDLE_VALUE;
    }
    return (HANDLE)fh;
}

static inline BOOL FindClose(HANDLE h) {
    if (h == INVALID_HANDLE_VALUE || !h) return FALSE;
    PosixFindHandle *fh = (PosixFindHandle *)h;
    if (fh->dir) closedir(fh->dir);
    free(fh);
    return TRUE;
}
#endif
