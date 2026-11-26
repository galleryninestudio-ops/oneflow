#include <jni.h>
#include "quickjs.h"
#include <string.h>

JNIEXPORT jstring JNICALL
Java_ai_oneflow_poc_QuickJsProbe_evalSync(JNIEnv *env, jclass clazz, jstring code) {
    (void)clazz;
    const char *src = (*env)->GetStringUTFChars(env, code, NULL);
    if (!src) return (*env)->NewStringUTF(env, "ERR: null input");

    JSRuntime *rt = JS_NewRuntime();
    JS_SetMemoryLimit(rt, 4 * 1024 * 1024);
    JS_SetMaxStackSize(rt, 256 * 1024);
    JSContext *ctx = JS_NewContext(rt);

    JSValue val = JS_Eval(ctx, src, strlen(src), "<jni-poc>", JS_EVAL_TYPE_GLOBAL);
    (*env)->ReleaseStringUTFChars(env, code, src);

    const char *out_c = NULL;
    JSValue json_or_exc = JS_UNDEFINED;
    if (JS_IsException(val)) {
        json_or_exc = JS_GetException(ctx);
        out_c = JS_ToCString(ctx, json_or_exc);
    } else {
        json_or_exc = JS_JSONStringify(ctx, val, JS_UNDEFINED, JS_UNDEFINED);
        out_c = JS_ToCString(ctx, JS_IsException(json_or_exc) ? val : json_or_exc);
    }

    jstring result = (*env)->NewStringUTF(env, out_c ? out_c : "undefined");
    if (out_c) JS_FreeCString(ctx, out_c);
    JS_FreeValue(ctx, json_or_exc);
    JS_FreeValue(ctx, val);
    JS_FreeContext(ctx);
    JS_FreeRuntime(rt);
    return result;
}
