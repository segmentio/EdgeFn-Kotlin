package com.segment.analytics.substrata.kotlin.j2v8

import android.util.Log
import com.eclipsesource.v8.JavaCallback
import com.eclipsesource.v8.V8
import com.eclipsesource.v8.V8Array
import com.eclipsesource.v8.V8Function
import com.eclipsesource.v8.V8Object
import com.eclipsesource.v8.V8ScriptCompilationException
import com.eclipsesource.v8.V8ScriptExecutionException
import com.segment.analytics.substrata.kotlin.JSEngineError
import com.segment.analytics.substrata.kotlin.JSValue
import com.segment.analytics.substrata.kotlin.JavascriptDataBridge
import com.segment.analytics.substrata.kotlin.JavascriptEngine
import com.segment.analytics.substrata.kotlin.JavascriptErrorHandler
import com.segment.analytics.substrata.kotlin.wrapAsJSValue
import io.alicorn.v8.V8JavaAdapter
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.reflect.KClass

private const val TimeOutInSeconds = 120L

/**
 * JSEngine singleton.  Due to the performance cost of creating runtimes in J2V8,
 * we'll use a singleton primarily; though it is possible to create an instance
 * of your own as well.
 */
class J2V8Engine : JavascriptEngine {

    override lateinit var bridge: J2V8DataBridge

    // All interaction with underlying runtime must be synchronized on the jsExecutor
    internal val jsExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    lateinit var underlying: V8

    // Main errorHandler that can be set by user. This allows us to handle any exceptions,
    // without worrying about propagating errors to the application and crashing it
    var errorHandler: JavascriptErrorHandler? = null

    init {
        jsExecutor.await {
            underlying = V8.createV8Runtime()
            // Following APIs are being called on jsExecutor and should not explicitly use jsExecutor
            setupConsole()
            setupDataBridge()
        }
    }

    override fun release() {
        underlying.release(false)
    }

    override fun loadBundle(bundleStream: InputStream, completion: (JSEngineError?) -> Unit) {
        var jsError: JSEngineError? = null
        val script: String? = BufferedReader(bundleStream.reader()).use {
            try {
                it.readText()
            } catch (e: IOException) {
                jsError = JSEngineError.UnableToLoad
                null
            }
        }

        script?.let {
            jsExecutor.sync {
                underlying.executeScript(it)
            }
        }
        completion(jsError)
    }

    override fun get(key: String): JSValue {
        val r = jsExecutor.await {
            var result: Any? = null
            underlying.memScope {
                underlying.get(key).let { value ->
                    if (!value.isNull()) {
                        result = value
                    } else {
                        underlying.executeScript(key).let { v ->
                            if (!v.isNull()) {
                                result = v
                            }
                        }
                    }
                }
            }
            wrapAsJSValue(result)
        }
        return r
    }

    override fun set(key: String, value: JSValue) {
        jsExecutor.sync {
            underlying.memScope {
                when (value) {
                    is JSValue.JSString -> {
                        underlying.add(key, value.content)
                    }
                    is JSValue.JSBool -> {
                        underlying.add(key, value.content)
                    }
                    is JSValue.JSDouble -> {
                        underlying.add(key, value.content)
                    }
                    is JSValue.JSInt -> {
                        underlying.add(key, value.content)
                    }
                    is JSValue.JSObject -> {
                        val jsRep = value.content
                        jsRep?.let {
                            underlying.add(key, underlying.toV8Object(it))
                        }
                    }
                    is JSValue.JSArray -> {
                        val jsRep = value.content
                        jsRep?.let {
                            underlying.add(key, underlying.toV8Array(it))
                        }
                    }
                    is JSValue.JSFunction -> {
                        underlying.add(key, value.fn)
                    }
                    JSValue.JSUndefined -> {
                        // omit no point in setting a key with undefined value, right?
                        underlying.addUndefined(key)
                    }
                }
            }
        }
    }

    override fun <T : Any> expose(key: String, value: T) {
        jsExecutor.sync {
            V8JavaAdapter.injectObject(key, value, underlying)
        }
    }

    override fun <C : Any> expose(clazz: KClass<C>, className: String) {
        jsExecutor.sync {
            V8JavaAdapter.injectClass(className, clazz.java, underlying)
        }
    }

    override fun expose(function: JSValue.JSFunction, functionName: String) {
        jsExecutor.sync {
            underlying.add(functionName, function.fn)
        }
    }

    override fun extend(objectName: String, function: JSValue.JSFunction, functionName: String) {
        /*
          If already exists
          -> if an object, extend it
          -> else, reportError
          else create it
         */
        jsExecutor.sync {
            val v8Obj: V8Object? = underlying.get(objectName).let { value ->
                when {
                    value.isNull() -> {
                        V8Object(underlying)
                    }
                    value is V8Object -> {
                        value
                    }
                    else -> {
                        reportError(JSEngineError.EvaluationError("attempting to add fn to a non-object value"))
                        null
                    }
                }
            }
            v8Obj?.let {
                it.add(functionName, function.fn)
                underlying.add(objectName, it)
            }
        }
    }

    override fun call(function: String, params: List<JSValue>): JSValue {
        val result = jsExecutor.await {
            underlying.memScope {
                val parameters = V8Array(underlying).apply {
                    params.forEach { value ->
                        when (value) {
                            is JSValue.JSString -> push(value.content)
                            is JSValue.JSBool -> push(value.content)
                            is JSValue.JSInt -> push(value.content)
                            is JSValue.JSDouble -> push(value.content)
                            is JSValue.JSArray -> {
                                val jsRep = value.content
                                jsRep?.let {
                                    push(underlying.toV8Array(it))
                                }
                            }
                            is JSValue.JSObject -> {
                                val jsRep = value.content
                                jsRep?.let {
                                    push(underlying.toV8Object(it))
                                }
                            }
                            is JSValue.JSUndefined -> pushUndefined()
                        }
                    }
                }
                wrapAsJSValue(underlying.executeFunction(function, parameters))
            }
        }
        return result
    }

    override fun call(
        receiver: JSValue.JSObjectReference,
        function: String,
        params: List<JSValue>
    ): JSValue {
        val result = jsExecutor.await {
            underlying.memScope {
                val parameters = V8Array(underlying).apply {
                    params.forEach { value ->
                        when (value) {
                            is JSValue.JSString -> push(value.content)
                            is JSValue.JSBool -> push(value.content)
                            is JSValue.JSInt -> push(value.content)
                            is JSValue.JSDouble -> push(value.content)
                            is JSValue.JSArray -> {
                                val jsRep = value.content
                                jsRep?.let {
                                    push(underlying.toV8Array(it))
                                }
                            }
                            is JSValue.JSObject -> {
                                val jsRep = value.content
                                jsRep?.let {
                                    push(underlying.toV8Object(it))
                                }
                            }
                            is JSValue.JSUndefined -> pushUndefined()
                        }
                    }
                }
                wrapAsJSValue(receiver.ref.executeFunction(function, parameters))
            }
        }
        return result
    }

    override fun execute(script: String): JSValue {
        val result = jsExecutor.await {
            val r = try {
                underlying.executeScript(script)
            } catch (ex: Exception) {
                ex.printStackTrace()
                Log.d("PRAY", ex.toString())
                null
            }
            wrapAsJSValue(r)
        }
        return result
    }

    /**
     * Internal API to synchronize interactions with the V8 runtime,
     * and return value wrapped as a JSValue
     */
    internal fun syncRunEngine(closure: (V8) -> Any?): JSValue {
        val result = syncRun(closure)
        return wrapAsJSValue(result)
    }

    /*
    * PRIVATE APIs
    * Note: Since all public APIs are implicitly synchronized on the jsExecutor (single-threaded)
    *       ensure that any further interactions do not cause a deadlock
    * */

    private fun <T> syncRun(closure: (V8) -> T?): T? {
        val result = jsExecutor.await {
            closure(underlying)
        }
        return result
    }

    private fun reportError(error: JSEngineError) {
        errorHandler?.let { it(error) }
    }

    private fun ExecutorService.sync(runnable: Runnable) {
        try {
            submit(runnable).get(TimeOutInSeconds, TimeUnit.SECONDS)
        } catch (ex: Exception) {
            processException(ex)
        }
    }

    private fun <T> ExecutorService.await(callable: Callable<T>): T {
        return try {
            submit(callable).get(TimeOutInSeconds, TimeUnit.SECONDS)
        } catch (ex: Exception) {
            ex.printStackTrace()
            processException(ex) as T
        }
    }

    // wrap exception in JSEngineError and return undefined if its a reference error
    private fun processException(ex: Exception): JSValue {
        var returnVal: JSValue = JSValue.JSUndefined
        when (ex) {
            is ExecutionException -> {
                when (val cause = ex.cause) {
                    is V8ScriptExecutionException -> {
                        reportError(JSEngineError.EvaluationError(cause.sourceLine))
                    }
                    is V8ScriptCompilationException -> {
                        if (cause.jsMessage.contains("ReferenceError")) {
                            // ReferenceError signifies undefined value
                            returnVal = JSValue.JSUndefined
                        } else {
                            reportError(JSEngineError.EvaluationError(cause.sourceLine))
                        }
                    }
                    else -> reportError(JSEngineError.UnknownError(ex))
                }
            }
            is TimeoutException -> {
                reportError(JSEngineError.TimeoutError(ex.message ?: ""))
            }
            else -> {
                reportError(JSEngineError.UnknownError(ex))
            }
        }
        return returnVal
    }

    /* ===========================================================================
    APIs being called on the jsExecutor and should not be synchronized explicitly
    ============================================================================== */
    private fun setupConsole() {
        // TODO add more versatility + Android log support
        val v8Console = V8Object(underlying)
        v8Console.registerJavaMethod({ _, v8Array ->
            val msg = jsValueToString(v8Array[0])
            println("[JSConsole.I] - $msg")
        }, "log")
        v8Console.registerJavaMethod({ _, v8Array ->
            val msg = jsValueToString(v8Array)
            println("[JSConsole.E] - $msg")
        }, "err")
        underlying.add("console", v8Console)
    }

    private fun setupDataBridge() {
        bridge = J2V8DataBridge(this)
    }

}

fun J2V8Engine.expose(function: JavaCallback, functionName: String) {
    expose(JSValue.JSFunction(V8Function(underlying, function)), functionName)
}

fun J2V8Engine.extend(objectName: String, function: JavaCallback, functionName: String) {
    extend(objectName, JSValue.JSFunction(V8Function(underlying, function)), functionName)
}

private fun Any?.isNull(): Boolean {
    return this == null
}

private fun jsValueToString(value: Any?): String {
    return when (value) {
        is Boolean -> value.toString()
        is Int -> value.toString()
        is Double -> value.toString()
        is String -> value.toString()
        is V8Array -> buildString {
            for (i in 0 until value.length() - 1) {
                append(jsValueToString(value.get(i)))
                append(", ")
            }
        }
        is V8Object -> buildString {
            for (key in value.keys) {
                append(jsValueToString(value.get(key)))
                append(", ")
            }
        }
        null -> "null"
        else -> "undefined"
    }
}