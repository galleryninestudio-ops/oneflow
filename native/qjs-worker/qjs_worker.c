#define _GNU_SOURCE
#include "quickjs.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

/*
 * Oneflow Isolated QuickJS-NG Worker Process (stdin/stdout JSON-lines IPC).
 * - Clears inherited environment variables
 * - Exposes ONLY __hostCall(toolId, argsJson) -> Promise<any>
 * - Zero std, os, require, filesystem, or socket bindings
 * - Enforces heap limit, stack limit, and uncatchable interrupt timeout
 */

typedef struct {
    struct timespec start;
    long timeout_ms;
    int interrupted;
} Watchdog;

typedef struct PendingCall {
    int call_id;
    JSContext *ctx;
    JSValue resolve;
    JSValue reject;
    struct PendingCall *next;
} PendingCall;

static PendingCall *g_calls = NULL;
static int g_next_call_id = 1;

static long elapsed_ms(const struct timespec *start) {
    struct timespec now;
    clock_gettime(CLOCK_MONOTONIC, &now);
    return (now.tv_sec - start->tv_sec) * 1000L +
           (now.tv_nsec - start->tv_nsec) / 1000000L;
}

static int interrupt_cb(JSContext *ctx, void *opaque) {
    (void)ctx;
    Watchdog *wd = (Watchdog *)opaque;
    if (elapsed_ms(&wd->start) > wd->timeout_ms) {
        wd->interrupted = 1;
        return 1;
    }
    return 0;
}

/* Write a JSON-escaped C string to stdout */
static void print_json_str(const char *s) {
    putchar('"');
    for (const unsigned char *p = (const unsigned char *)s; *p; p++) {
        unsigned char c = *p;
        if (c == '"') fputs("\\\"", stdout);
        else if (c == '\\') fputs("\\\\", stdout);
        else if (c == '\b') fputs("\\b", stdout);
        else if (c == '\f') fputs("\\f", stdout);
        else if (c == '\n') fputs("\\n", stdout);
        else if (c == '\r') fputs("\\r", stdout);
        else if (c == '\t') fputs("\\t", stdout);
        else if (c < 0x20) printf("\\u%04x", c);
        else putchar(c);
    }
    putchar('"');
}

static JSValue host_call_fn(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    (void)this_val;
    if (argc < 2) {
        return JS_ThrowTypeError(ctx, "__hostCall(toolId, argsJson) requires 2 arguments");
    }
    const char *tool_id = JS_ToCString(ctx, argv[0]);
    const char *args_json = JS_ToCString(ctx, argv[1]);
    if (!tool_id || !args_json) {
        if (tool_id) JS_FreeCString(ctx, tool_id);
        if (args_json) JS_FreeCString(ctx, args_json);
        return JS_EXCEPTION;
    }

    JSValue resolving[2];
    JSValue promise = JS_NewPromiseCapability(ctx, resolving);
    if (JS_IsException(promise)) {
        JS_FreeCString(ctx, tool_id);
        JS_FreeCString(ctx, args_json);
        return promise;
    }

    int cid = g_next_call_id++;
    PendingCall *node = malloc(sizeof(PendingCall));
    node->call_id = cid;
    node->ctx = ctx;
    node->resolve = resolving[0];
    node->reject = resolving[1];
    node->next = g_calls;
    g_calls = node;

    fputs("{\"type\":\"call\",\"callId\":", stdout);
    printf("%d", cid);
    fputs(",\"toolId\":", stdout);
    print_json_str(tool_id);
    fputs(",\"argsJson\":", stdout);
    print_json_str(args_json);
    fputs("}\n", stdout);
    fflush(stdout);

    JS_FreeCString(ctx, tool_id);
    JS_FreeCString(ctx, args_json);
    return promise;
}

static PendingCall *take_pending_call(int call_id) {
    PendingCall **pp = &g_calls;
    while (*pp) {
        if ((*pp)->call_id == call_id) {
            PendingCall *found = *pp;
            *pp = found->next;
            return found;
        }
        pp = &(*pp)->next;
    }
    return NULL;
}

static char *read_line_dynamic(FILE *fp) {
    size_t cap = 4096;
    size_t len = 0;
    char *buf = malloc(cap);
    if (!buf) return NULL;
    int c;
    while ((c = fgetc(fp)) != EOF) {
        if (c == '\n') break;
        if (len + 2 >= cap) {
            cap *= 2;
            char *nb = realloc(buf, cap);
            if (!nb) {
                free(buf);
                return NULL;
            }
            buf = nb;
        }
        buf[len++] = (char)c;
    }
    if (len == 0 && c == EOF) {
        free(buf);
        return NULL;
    }
    buf[len] = '\0';
    return buf;
}

int main(void) {
    clearenv();
    for (int fd = 3; fd < 256; fd++) close(fd);

    char *init_line = read_line_dynamic(stdin);
    if (!init_line) return 2;

    JSRuntime *rt = JS_NewRuntime();
    JSContext *ctx = JS_NewContext(rt);

    /* Parse init JSON via QuickJS itself so we don't duplicate JSON parsing in C */
    JSValue init_obj = JS_ParseJSON(ctx, init_line, strlen(init_line), "<init>");
    free(init_line);
    if (JS_IsException(init_obj)) {
        fputs("{\"type\":\"result\",\"ok\":false,\"error\":\"Invalid init JSON\"}\n", stdout);
        fflush(stdout);
        JS_FreeContext(ctx);
        JS_FreeRuntime(rt);
        return 2;
    }

    JSValue mem_val = JS_GetPropertyStr(ctx, init_obj, "maxMemoryBytes");
    JSValue to_val = JS_GetPropertyStr(ctx, init_obj, "timeoutMs");
    JSValue code_val = JS_GetPropertyStr(ctx, init_obj, "code");

    int64_t max_mem = 16 * 1024 * 1024;
    int64_t timeout_ms = 10000;
    JS_ToInt64(ctx, &max_mem, mem_val);
    JS_ToInt64(ctx, &timeout_ms, to_val);
    const char *code_c = JS_ToCString(ctx, code_val);

    JS_FreeValue(ctx, mem_val);
    JS_FreeValue(ctx, to_val);
    JS_FreeValue(ctx, code_val);
    JS_FreeValue(ctx, init_obj);

    if (!code_c) {
        fputs("{\"type\":\"result\",\"ok\":false,\"error\":\"Missing script code\"}\n", stdout);
        fflush(stdout);
        JS_FreeContext(ctx);
        JS_FreeRuntime(rt);
        return 2;
    }

    JS_SetMemoryLimit(rt, (size_t)max_mem);
    JS_SetMaxStackSize(rt, 512 * 1024);

    Watchdog wd;
    clock_gettime(CLOCK_MONOTONIC, &wd.start);
    wd.timeout_ms = (long)timeout_ms;
    wd.interrupted = 0;
    JS_SetInterruptHandler(rt, interrupt_cb, &wd);

    JSValue global = JS_GetGlobalObject(ctx);
    JS_SetPropertyStr(ctx, global, "__hostCall", JS_NewCFunction(ctx, host_call_fn, "__hostCall", 2));
    JS_FreeValue(ctx, global);

    size_t wrap_cap = strlen(code_c) + 128;
    char *wrapped = malloc(wrap_cap);
    snprintf(wrapped, wrap_cap, "(async function() {\n%s\n})()", code_c);
    JS_FreeCString(ctx, code_c);

    JSValue promise = JS_Eval(ctx, wrapped, strlen(wrapped), "<flowcode>", JS_EVAL_TYPE_GLOBAL);
    free(wrapped);

    if (JS_IsException(promise)) {
        JSValue exc = JS_GetException(ctx);
        const char *msg = JS_ToCString(ctx, exc);
        fputs("{\"type\":\"result\",\"ok\":false,\"interrupted\":", stdout);
        fputs(wd.interrupted ? "true" : "false", stdout);
        fputs(",\"error\":", stdout);
        print_json_str(msg ? msg : "JS execution error");
        fputs("}\n", stdout);
        fflush(stdout);
        if (msg) JS_FreeCString(ctx, msg);
        JS_FreeValue(ctx, exc);
        JS_FreeValue(ctx, promise);
        JS_FreeContext(ctx);
        JS_FreeRuntime(rt);
        return 0;
    }

    while (1) {
        while (JS_IsJobPending(rt)) {
            JSContext *pctx = NULL;
            if (JS_ExecutePendingJob(rt, &pctx) < 0) break;
        }
        if (JS_PromiseState(ctx, promise) != JS_PROMISE_PENDING) break;
        if (!g_calls) break;

        char *reply_line = read_line_dynamic(stdin);
        if (!reply_line) break;

        JSValue rep = JS_ParseJSON(ctx, reply_line, strlen(reply_line), "<reply>");
        free(reply_line);
        if (JS_IsException(rep)) {
            JS_FreeValue(ctx, rep);
            continue;
        }

        JSValue cid_v = JS_GetPropertyStr(ctx, rep, "callId");
        JSValue ok_v = JS_GetPropertyStr(ctx, rep, "ok");
        JSValue payload_v = JS_GetPropertyStr(ctx, rep, "payloadJson");
        int32_t cid = -1;
        JS_ToInt32(ctx, &cid, cid_v);
        int is_ok = JS_ToBool(ctx, ok_v);
        const char *payload_c = JS_ToCString(ctx, payload_v);

        PendingCall *pc = take_pending_call(cid);
        if (pc) {
            if (is_ok > 0) {
                JSValue parsed = payload_c
                    ? JS_ParseJSON(ctx, payload_c, strlen(payload_c), "<tool_res>")
                    : JS_UNDEFINED;
                if (JS_IsException(parsed)) {
                    JS_FreeValue(ctx, parsed);
                    parsed = JS_NewString(ctx, payload_c ? payload_c : "");
                }
                JSValue r = JS_Call(ctx, pc->resolve, JS_UNDEFINED, 1, &parsed);
                JS_FreeValue(ctx, r);
                JS_FreeValue(ctx, parsed);
            } else {
                JSValue err = JS_NewString(ctx, payload_c ? payload_c : "Tool call failed");
                JSValue r = JS_Call(ctx, pc->reject, JS_UNDEFINED, 1, &err);
                JS_FreeValue(ctx, r);
                JS_FreeValue(ctx, err);
            }
            JS_FreeValue(ctx, pc->resolve);
            JS_FreeValue(ctx, pc->reject);
            free(pc);
        }

        if (payload_c) JS_FreeCString(ctx, payload_c);
        JS_FreeValue(ctx, cid_v);
        JS_FreeValue(ctx, ok_v);
        JS_FreeValue(ctx, payload_v);
        JS_FreeValue(ctx, rep);
    }

    JSPromiseStateEnum st = JS_PromiseState(ctx, promise);
    JSValue res = JS_PromiseResult(ctx, promise);
    if (st == JS_PROMISE_FULFILLED) {
        JSValue json_val = JS_JSONStringify(ctx, res, JS_UNDEFINED, JS_UNDEFINED);
        const char *s = JS_ToCString(ctx, JS_IsException(json_val) ? res : json_val);
        fputs("{\"type\":\"result\",\"ok\":true,\"output\":", stdout);
        print_json_str(s ? s : "null");
        fputs("}\n", stdout);
        if (s) JS_FreeCString(ctx, s);
        JS_FreeValue(ctx, json_val);
    } else {
        const char *s = JS_ToCString(ctx, res);
        fputs("{\"type\":\"result\",\"ok\":false,\"interrupted\":", stdout);
        fputs(wd.interrupted ? "true" : "false", stdout);
        fputs(",\"error\":", stdout);
        print_json_str(s ? s : "Promise rejected");
        fputs("}\n", stdout);
        if (s) JS_FreeCString(ctx, s);
    }
    fflush(stdout);

    while (g_calls) {
        PendingCall *n = g_calls->next;
        JS_FreeValue(ctx, g_calls->resolve);
        JS_FreeValue(ctx, g_calls->reject);
        free(g_calls);
        g_calls = n;
    }
    JS_FreeValue(ctx, res);
    JS_FreeValue(ctx, promise);
    JS_FreeContext(ctx);
    JS_FreeRuntime(rt);
    return 0;
}
