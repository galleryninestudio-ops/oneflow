#define _POSIX_C_SOURCE 200809L
#include <jni.h>
#include "quickjs.h"
#include <stdlib.h>
#include <string.h>
#include <time.h>

typedef struct {
    struct timespec start;
    long timeout_ms;
    int interrupted;
} Watchdog;

typedef struct {
    JNIEnv *env;
    jobject bridge;
    jmethodID on_host_call;
} JniBridgeCtx;

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

static JSValue jni_host_call(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    (void)this_val;
    JniBridgeCtx *b = (JniBridgeCtx *)JS_GetContextOpaque(ctx);
    if (!b || argc < 2) {
        return JS_ThrowTypeError(ctx, "__hostCall requires (toolId, argsJson)");
    }
    const char *tool_id = JS_ToCString(ctx, argv[0]);
    const char *args_json = JS_ToCString(ctx, argv[1]);
    if (!tool_id || !args_json) {
        if (tool_id) JS_FreeCString(ctx, tool_id);
        if (args_json) JS_FreeCString(ctx, args_json);
        return JS_EXCEPTION;
    }

    jstring j_tool = (*b->env)->NewStringUTF(b->env, tool_id);
    jstring j_args = (*b->env)->NewStringUTF(b->env, args_json);
    JS_FreeCString(ctx, tool_id);
    JS_FreeCString(ctx, args_json);

    jstring j_reply = (jstring)(*b->env)->CallObjectMethod(b->env, b->bridge, b->on_host_call, j_tool, j_args);
    (*b->env)->DeleteLocalRef(b->env, j_tool);
    (*b->env)->DeleteLocalRef(b->env, j_args);

    if ((*b->env)->ExceptionCheck(b->env)) {
        (*b->env)->ExceptionClear(b->env);
        return JS_NewSettledPromise(ctx, true, JS_NewString(ctx, "Host IPC exception"));
    }

    const char *reply_c = j_reply ? (*b->env)->GetStringUTFChars(b->env, j_reply, NULL) : NULL;
    JSValue rep_obj = reply_c ? JS_ParseJSON(ctx, reply_c, strlen(reply_c), "<ipc_reply>") : JS_UNDEFINED;
    if (reply_c) (*b->env)->ReleaseStringUTFChars(b->env, j_reply, reply_c);
    if (j_reply) (*b->env)->DeleteLocalRef(b->env, j_reply);

    if (JS_IsException(rep_obj)) {
        JS_FreeValue(ctx, rep_obj);
        return JS_NewSettledPromise(ctx, true, JS_NewString(ctx, "Invalid host IPC reply"));
    }

    JSValue ok_v = JS_GetPropertyStr(ctx, rep_obj, "ok");
    JSValue payload_v = JS_GetPropertyStr(ctx, rep_obj, "payloadJson");
    int is_ok = JS_ToBool(ctx, ok_v);
    const char *payload_c = JS_ToCString(ctx, payload_v);

    JSValue settled;
    if (is_ok > 0) {
        JSValue parsed = payload_c ? JS_ParseJSON(ctx, payload_c, strlen(payload_c), "<tool_res>") : JS_UNDEFINED;
        if (JS_IsException(parsed)) {
            JS_FreeValue(ctx, parsed);
            parsed = JS_NewString(ctx, payload_c ? payload_c : "");
        }
        settled = JS_NewSettledPromise(ctx, false, parsed);
        JS_FreeValue(ctx, parsed);
    } else {
        JSValue err = JS_NewString(ctx, payload_c ? payload_c : "Tool denied or failed");
        settled = JS_NewSettledPromise(ctx, true, err);
        JS_FreeValue(ctx, err);
    }

    if (payload_c) JS_FreeCString(ctx, payload_c);
    JS_FreeValue(ctx, ok_v);
    JS_FreeValue(ctx, payload_v);
    JS_FreeValue(ctx, rep_obj);
    return settled;
}

JNIEXPORT jstring JNICALL
Java_ai_oneflow_app_worker_QuickJsNativeBridge_nativeExecute(
    JNIEnv *env,
    jclass clazz,
    jstring j_code,
    jlong timeout_ms,
    jlong max_memory_bytes,
    jobject bridge
) {
    (void)clazz;
    const char *code_c = (*env)->GetStringUTFChars(env, j_code, NULL);
    if (!code_c) return (*env)->NewStringUTF(env, "{\"ok\":false,\"error\":\"null code\"}");

    jclass bridge_cls = (*env)->GetObjectClass(env, bridge);
    jmethodID mid = (*env)->GetMethodID(env, bridge_cls, "onHostCall", "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;");
    (*env)->DeleteLocalRef(env, bridge_cls);

    JniBridgeCtx bctx = { .env = env, .bridge = bridge, .on_host_call = mid };

    JSRuntime *rt = JS_NewRuntime();
    JS_SetMemoryLimit(rt, (size_t)max_memory_bytes);
    JS_SetMaxStackSize(rt, 512 * 1024);

    Watchdog wd;
    clock_gettime(CLOCK_MONOTONIC, &wd.start);
    wd.timeout_ms = (long)timeout_ms;
    wd.interrupted = 0;
    JS_SetInterruptHandler(rt, interrupt_cb, &wd);

    JSContext *ctx = JS_NewContext(rt);
    JS_SetContextOpaque(ctx, &bctx);

    JSValue global = JS_GetGlobalObject(ctx);
    JS_SetPropertyStr(ctx, global, "__hostCall", JS_NewCFunction(ctx, jni_host_call, "__hostCall", 2));
    JS_FreeValue(ctx, global);

    size_t wrap_cap = strlen(code_c) + 128;
    char *wrapped = malloc(wrap_cap);
    snprintf(wrapped, wrap_cap, "(async function() {\n%s\n})()", code_c);
    (*env)->ReleaseStringUTFChars(env, j_code, code_c);

    JSValue promise = JS_Eval(ctx, wrapped, strlen(wrapped), "<flowcode>", JS_EVAL_TYPE_GLOBAL);
    free(wrapped);

    JSValue out_obj = JS_NewObject(ctx);
    if (JS_IsException(promise)) {
        JSValue exc = JS_GetException(ctx);
        const char *msg = JS_ToCString(ctx, exc);
        JS_SetPropertyStr(ctx, out_obj, "ok", JS_NewBool(ctx, 0));
        JS_SetPropertyStr(ctx, out_obj, "interrupted", JS_NewBool(ctx, wd.interrupted));
        JS_SetPropertyStr(ctx, out_obj, "error", JS_NewString(ctx, msg ? msg : "JS error"));
        if (msg) JS_FreeCString(ctx, msg);
        JS_FreeValue(ctx, exc);
    } else {
        while (JS_IsJobPending(rt)) {
            JSContext *pctx = NULL;
            if (JS_ExecutePendingJob(rt, &pctx) < 0) break;
        }
        JSPromiseStateEnum st = JS_PromiseState(ctx, promise);
        JSValue res = JS_PromiseResult(ctx, promise);
        if (st == JS_PROMISE_FULFILLED) {
            JSValue json_val = JS_JSONStringify(ctx, res, JS_UNDEFINED, JS_UNDEFINED);
            const char *s = JS_ToCString(ctx, JS_IsException(json_val) ? res : json_val);
            JS_SetPropertyStr(ctx, out_obj, "ok", JS_NewBool(ctx, 1));
            JS_SetPropertyStr(ctx, out_obj, "output", JS_NewString(ctx, s ? s : "null"));
            if (s) JS_FreeCString(ctx, s);
            JS_FreeValue(ctx, json_val);
        } else {
            const char *s = JS_ToCString(ctx, res);
            JS_SetPropertyStr(ctx, out_obj, "ok", JS_NewBool(ctx, 0));
            JS_SetPropertyStr(ctx, out_obj, "interrupted", JS_NewBool(ctx, wd.interrupted));
            JS_SetPropertyStr(ctx, out_obj, "error", JS_NewString(ctx, s ? s : "Rejected"));
            if (s) JS_FreeCString(ctx, s);
        }
        JS_FreeValue(ctx, res);
    }
    JS_FreeValue(ctx, promise);

    /* Lift memory limit before serializing final response/error JSON */
    JS_SetMemoryLimit(rt, (size_t)-1);
    JSValue out_json = JS_JSONStringify(ctx, out_obj, JS_UNDEFINED, JS_UNDEFINED);
    const char *out_c = JS_ToCString(ctx, out_json);
    jstring result = (*env)->NewStringUTF(env, out_c ? out_c : "{\"ok\":false,\"error\":\"serialize\"}");
    if (out_c) JS_FreeCString(ctx, out_c);
    JS_FreeValue(ctx, out_json);
    JS_FreeValue(ctx, out_obj);

    JS_FreeContext(ctx);
    JS_FreeRuntime(rt);
    return result;
}
