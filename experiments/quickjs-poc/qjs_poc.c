#define _POSIX_C_SOURCE 200809L
#include "quickjs.h"
#include <assert.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

typedef struct {
    struct timespec start;
    long timeout_ms;
    int interrupted;
} Watchdog;

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

/* Mock async host capability: returns a Promise resolved after draining one simulated host tick. */
typedef struct {
    JSContext *ctx;
    JSValue resolve;
    JSValue reject;
    char *tool_name;
    char *args_json;
    int pending;
} PendingHostCall;

static PendingHostCall g_pending = {0};

static JSValue host_call_mock(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    (void)this_val;
    if (argc < 2) {
        return JS_ThrowTypeError(ctx, "hostCall(toolName, argsJson) requires 2 args");
    }
    const char *tool = JS_ToCString(ctx, argv[0]);
    const char *args = JS_ToCString(ctx, argv[1]);
    if (!tool || !args) {
        if (tool) JS_FreeCString(ctx, tool);
        if (args) JS_FreeCString(ctx, args);
        return JS_EXCEPTION;
    }

    JSValue resolving[2];
    JSValue promise = JS_NewPromiseCapability(ctx, resolving);
    if (JS_IsException(promise)) {
        JS_FreeCString(ctx, tool);
        JS_FreeCString(ctx, args);
        return promise;
    }

    g_pending.ctx = ctx;
    g_pending.resolve = resolving[0];
    g_pending.reject = resolving[1];
    g_pending.tool_name = strdup(tool);
    g_pending.args_json = strdup(args);
    g_pending.pending = 1;

    JS_FreeCString(ctx, tool);
    JS_FreeCString(ctx, args);
    return promise;
}

static void settle_pending_host_call(JSRuntime *rt) {
    if (!g_pending.pending) return;
    JSContext *ctx = g_pending.ctx;

    if (strcmp(g_pending.tool_name, "fail.tool") == 0) {
        JSValue err = JS_NewString(ctx, "mock capability denied");
        JSValue r = JS_Call(ctx, g_pending.reject, JS_UNDEFINED, 1, &err);
        JS_FreeValue(ctx, err);
        JS_FreeValue(ctx, r);
    } else {
        char buf[512];
        snprintf(buf, sizeof(buf), "{\"tool\":\"%s\",\"echo\":%s}", g_pending.tool_name, g_pending.args_json);
        JSValue res = JS_NewString(ctx, buf);
        JSValue r = JS_Call(ctx, g_pending.resolve, JS_UNDEFINED, 1, &res);
        JS_FreeValue(ctx, res);
        JS_FreeValue(ctx, r);
    }

    JS_FreeValue(ctx, g_pending.resolve);
    JS_FreeValue(ctx, g_pending.reject);
    free(g_pending.tool_name);
    free(g_pending.args_json);
    memset(&g_pending, 0, sizeof(g_pending));
    (void)rt;
}

static int run_async_body(
    const char *label,
    const char *body_js,
    size_t mem_limit,
    long timeout_ms,
    char *out_buf,
    size_t out_cap
) {
    JSRuntime *rt = JS_NewRuntime();
    assert(rt != NULL);
    JS_SetMemoryLimit(rt, mem_limit);
    JS_SetMaxStackSize(rt, 512 * 1024);

    Watchdog wd;
    clock_gettime(CLOCK_MONOTONIC, &wd.start);
    wd.timeout_ms = timeout_ms;
    wd.interrupted = 0;
    JS_SetInterruptHandler(rt, interrupt_cb, &wd);

    JSContext *ctx = JS_NewContext(rt);
    assert(ctx != NULL);

    JSValue global = JS_GetGlobalObject(ctx);
    JS_SetPropertyStr(ctx, global, "__hostCall", JS_NewCFunction(ctx, host_call_mock, "__hostCall", 2));
    JS_FreeValue(ctx, global);

    size_t len = strlen(body_js) + 128;
    char *wrapped = malloc(len);
    snprintf(wrapped, len, "(async function() {\n%s\n})()", body_js);

    JSValue val = JS_Eval(ctx, wrapped, strlen(wrapped), "<flowcode>", JS_EVAL_TYPE_GLOBAL);
    free(wrapped);

    int rc = 0;
    if (JS_IsException(val)) {
        JSValue exc = JS_GetException(ctx);
        const char *s = JS_ToCString(ctx, exc);
        snprintf(out_buf, out_cap, "COMPILE_OR_SYNC_ERR(interrupted=%d): %s", wd.interrupted, s ? s : "(null)");
        if (s) JS_FreeCString(ctx, s);
        JS_FreeValue(ctx, exc);
        JS_FreeValue(ctx, val);
        rc = wd.interrupted ? 2 : -1;
    } else {
        /* Pump Promise job queue + simulated async host calls */
        for (int iter = 0; iter < 1000; iter++) {
            while (JS_IsJobPending(rt)) {
                JSContext *pctx = NULL;
                int job_rc = JS_ExecutePendingJob(rt, &pctx);
                if (job_rc < 0) {
                    break;
                }
            }
            if (JS_PromiseState(ctx, val) != JS_PROMISE_PENDING) break;
            if (g_pending.pending) {
                settle_pending_host_call(rt);
            } else {
                break;
            }
        }

        JSPromiseStateEnum pstate = JS_PromiseState(ctx, val);
        JSValue pres = JS_PromiseResult(ctx, val);
        if (pstate == JS_PROMISE_FULFILLED) {
            JSValue json_val = JS_JSONStringify(ctx, pres, JS_UNDEFINED, JS_UNDEFINED);
            const char *s = JS_ToCString(ctx, JS_IsException(json_val) ? pres : json_val);
            snprintf(out_buf, out_cap, "OK: %s", s ? s : "undefined");
            if (s) JS_FreeCString(ctx, s);
            JS_FreeValue(ctx, json_val);
            rc = 0;
        } else {
            const char *s = JS_ToCString(ctx, pres);
            snprintf(out_buf, out_cap, "REJECTED(interrupted=%d): %s", wd.interrupted, s ? s : "(null)");
            if (s) JS_FreeCString(ctx, s);
            rc = wd.interrupted ? 2 : 1;
        }
        JS_FreeValue(ctx, pres);
        JS_FreeValue(ctx, val);
    }

    printf("[%s] rc=%d -> %s\n", label, rc, out_buf);
    JS_FreeContext(ctx);
    JS_FreeRuntime(rt);
    return rc;
}

int main(void) {
    char buf[1024];

    /* 1. Async await + host mock call */
    int r1 = run_async_body(
        "async_host_call",
        "const raw = await __hostCall('fs.stat', JSON.stringify({path:'README.md'}));\n"
        "return { parsed: JSON.parse(raw), note: 'await ok' };",
        8 * 1024 * 1024, 1000, buf, sizeof(buf)
    );
    assert(r1 == 0 && strstr(buf, "fs.stat") != NULL);

    /* 2. Error propagation from rejected host promise */
    int r2 = run_async_body(
        "error_propagation",
        "return await __hostCall('fail.tool', '{}');",
        8 * 1024 * 1024, 1000, buf, sizeof(buf)
    );
    assert(r2 == 1 && strstr(buf, "mock capability denied") != NULL);

    /* 3. Memory limit enforcement (2 MB cap) */
    int r3 = run_async_body(
        "memory_limit",
        "const a = new Array(2000000).fill(42); return a.length;",
        2 * 1024 * 1024, 1000, buf, sizeof(buf)
    );
    assert(r3 != 0);

    /* 4. Infinite-loop interruption (~50ms timeout) even inside try/catch */
    int r4 = run_async_body(
        "infinite_loop_interrupt",
        "try { while (true) {} } catch (e) { return 'swallowed'; }",
        8 * 1024 * 1024, 50, buf, sizeof(buf)
    );
    assert(r4 == 2 && strstr(buf, "interrupted=1") != NULL);

    /* 5. Verify std/os/require are not exposed */
    int r5 = run_async_body(
        "no_ambient_node_std",
        "return { hasRequire: typeof require, hasStd: typeof std, hasOs: typeof os };",
        8 * 1024 * 1024, 1000, buf, sizeof(buf)
    );
    assert(r5 == 0 && strstr(buf, "\"hasRequire\":\"undefined\"") != NULL);

    printf("ALL QUICKJS-NG PHASE 0 HOST CHECKS PASSED\n");
    return 0;
}
