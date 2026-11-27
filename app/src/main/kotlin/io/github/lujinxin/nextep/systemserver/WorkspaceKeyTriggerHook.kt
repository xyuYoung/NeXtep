package io.github.lujinxin.nextep.systemserver

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.view.KeyEvent
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.lujinxin.nextep.config.WorkspaceConfigContract
import io.github.lujinxin.nextep.logging.NeXtepLog
import io.github.lujinxin.nextep.trigger.TriggerBroadcastContract
import io.github.lujinxin.nextep.workspace.SystemServerWorkspaceBridge
import java.util.concurrent.atomic.AtomicBoolean

/** Intercepts a user-configured hardware key in AOSP PhoneWindowManager to toggle the workspace. */
internal object WorkspaceKeyTriggerHook {
    private val failed = AtomicBoolean(false)
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private val stateLock = Any()

    private var singleDownTime = -1L
    private var firstTapDownTime = -1L
    private var firstTapUpTime = 0L
    private var swallowingDoubleDownTime = -1L
    private var longDownTime = -1L
    private var longTriggered = false
    private var pendingLongPress: Runnable? = null

    fun install(module: XposedModule, loader: ClassLoader) {
        val pwmClass = loader.loadClass("com.android.server.policy.PhoneWindowManager")
        val contextField = generateSequence(pwmClass as Class<*>?) { it.superclass }
            .mapNotNull { runCatching { it.getDeclaredField("mContext") }.getOrNull() }
            .firstOrNull()
            ?.apply { isAccessible = true }
            ?: error("PhoneWindowManager#mContext not found")
        val interceptMethod = pwmClass.declaredMethods.firstOrNull { method ->
            method.name == "interceptKeyBeforeQueueing" &&
                method.parameterTypes.size == 2 &&
                method.parameterTypes[0] == KeyEvent::class.java &&
                method.parameterTypes[1] == Int::class.javaPrimitiveType
        }?.apply { isAccessible = true }
            ?: error("PhoneWindowManager#interceptKeyBeforeQueueing not found")

        module.hook(interceptMethod).intercept(object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                val consume = runCatching {
                    val event = chain.args.getOrNull(0) as? KeyEvent ?: return@runCatching false
                    val policyFlags = chain.args.getOrNull(1) as? Int ?: 0
                    val context = chain.thisObject?.let { contextField.get(it) as? Context }
                        ?: return@runCatching false
                    SystemServerWorkspaceBridge.startGeometryObservation(context)
                    handleKeyEvent(context, event, policyFlags)
                }.onFailure { error ->
                    if (failed.compareAndSet(false, true)) {
                        NeXtepLog.warn("key_trigger", "Hardware key interception failed open", error)
                    }
                }.getOrDefault(false)
                return if (consume) 0 else chain.proceed()
            }
        })
        NeXtepLog.info("key_trigger", "Installed PhoneWindowManager hardware key trigger hook")
    }

    private fun handleKeyEvent(context: Context, event: KeyEvent, policyFlags: Int): Boolean {
        val config = SystemServerWorkspaceBridge.observedKeyTriggerConfig()
        if (!config.enabled || config.keyCode <= 0 || event.keyCode != config.keyCode) {
            return false
        }
        if ((policyFlags and POLICY_FLAG_INJECTED) != 0) {
            return false
        }
        if (!isInteractiveAndUnlocked(context)) {
            resetState()
            return false
        }
        return when (config.mode) {
            WorkspaceConfigContract.KEY_TRIGGER_MODE_SINGLE -> handleSingle(context, event)
            WorkspaceConfigContract.KEY_TRIGGER_MODE_DOUBLE -> handleDouble(context, event)
            WorkspaceConfigContract.KEY_TRIGGER_MODE_LONG -> handleLong(context, event)
            else -> false
        }
    }

    private fun handleSingle(context: Context, event: KeyEvent): Boolean = synchronized(stateLock) {
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0) {
                    singleDownTime = event.downTime
                    true
                } else {
                    singleDownTime == event.downTime
                }
            }
            KeyEvent.ACTION_UP -> {
                if (singleDownTime != event.downTime) return false
                singleDownTime = -1L
                if ((event.flags and KeyEvent.FLAG_CANCELED) == 0) {
                    dispatchToggle(context)
                }
                true
            }
            else -> false
        }
    }

    private fun handleDouble(context: Context, event: KeyEvent): Boolean = synchronized(stateLock) {
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount > 0) {
                    return swallowingDoubleDownTime == event.downTime
                }
                val delta = event.eventTime - firstTapUpTime
                if (firstTapUpTime > 0L && delta in 0L..DOUBLE_PRESS_WINDOW_MS) {
                    swallowingDoubleDownTime = event.downTime
                    firstTapUpTime = 0L
                    firstTapDownTime = -1L
                    dispatchToggle(context)
                    true
                } else {
                    firstTapDownTime = event.downTime
                    false
                }
            }
            KeyEvent.ACTION_UP -> {
                if (swallowingDoubleDownTime == event.downTime) {
                    swallowingDoubleDownTime = -1L
                    return true
                }
                if (firstTapDownTime == event.downTime) {
                    firstTapDownTime = -1L
                    firstTapUpTime = if ((event.flags and KeyEvent.FLAG_CANCELED) == 0) {
                        event.eventTime
                    } else 0L
                }
                false
            }
            else -> false
        }
    }

    private fun handleLong(context: Context, event: KeyEvent): Boolean = synchronized(stateLock) {
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount > 0) {
                    return longDownTime == event.downTime && longTriggered
                }
                cancelLongPressLocked()
                val downTime = event.downTime
                longDownTime = downTime
                longTriggered = false
                val runnable = Runnable {
                    val shouldTrigger = synchronized(stateLock) {
                        if (longDownTime == downTime && !longTriggered) {
                            longTriggered = true
                            true
                        } else false
                    }
                    if (shouldTrigger && isInteractiveAndUnlocked(context)) {
                        dispatchToggle(context)
                    }
                }
                pendingLongPress = runnable
                mainHandler.postDelayed(runnable, LONG_PRESS_TIMEOUT_MS)
                false
            }
            KeyEvent.ACTION_UP -> {
                cancelLongPressLocked()
                val consumed = longDownTime == event.downTime && longTriggered
                longDownTime = -1L
                longTriggered = false
                consumed
            }
            else -> false
        }
    }

    private fun cancelLongPressLocked() {
        pendingLongPress?.let(mainHandler::removeCallbacks)
        pendingLongPress = null
    }

    private fun resetState() = synchronized(stateLock) {
        singleDownTime = -1L
        firstTapDownTime = -1L
        firstTapUpTime = 0L
        swallowingDoubleDownTime = -1L
        longDownTime = -1L
        longTriggered = false
        cancelLongPressLocked()
    }

    private fun isInteractiveAndUnlocked(context: Context): Boolean = runCatching {
        val power = context.getSystemService(PowerManager::class.java)
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        power?.isInteractive == true && keyguard?.isKeyguardLocked == false
    }.getOrDefault(false)

    private fun dispatchToggle(context: Context) {
        mainHandler.post {
            runCatching {
                val request = Intent(TriggerBroadcastContract.ACTION_KEY_TRIGGER_WORKSPACE)
                    .setPackage(TriggerBroadcastContract.SYSTEM_UI_PACKAGE)
                    .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                context.sendOrderedBroadcastAsUser(
                    request,
                    Process.myUserHandle(),
                    null,
                    null,
                    mainHandler,
                    TriggerBroadcastContract.RESULT_NOT_READY,
                    null,
                    null,
                )
            }.onFailure { error ->
                NeXtepLog.warn("key_trigger", "Unable to dispatch key trigger to SystemUI", error)
            }
        }
    }

    private const val POLICY_FLAG_INJECTED = 0x01000000
    private const val DOUBLE_PRESS_WINDOW_MS = 350L
    private const val LONG_PRESS_TIMEOUT_MS = 450L
}
