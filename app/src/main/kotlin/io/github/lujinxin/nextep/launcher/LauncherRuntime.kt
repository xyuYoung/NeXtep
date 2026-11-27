package io.github.lujinxin.nextep.launcher

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import io.github.lujinxin.nextep.framework.TaskInfoCompat
import io.github.lujinxin.nextep.logging.NeXtepLog
import io.github.lujinxin.nextep.trigger.TriggerBroadcastContract
import io.github.lujinxin.nextep.trigger.WorkspaceRecoveryPolicy
import io.github.lujinxin.nextep.workspace.SystemServerWorkspaceBridge
import java.util.concurrent.atomic.AtomicBoolean

object LauncherRuntime {
    @Volatile private var cachedAppContext: Context? = null
    private val receiverRegistered = AtomicBoolean(false)
    private val systemUiReceiverRegistered = AtomicBoolean(false)

    internal fun applicationContextOrNull(): Context? = cachedAppContext

    fun onHomeResumed(activity: Activity) {
        if (!LauncherPackageResolver.isWorkspaceSurface(activity)) return
        cachedAppContext = activity.applicationContext ?: activity
        LauncherTransformController.attach(activity)
        LauncherTransformController.setActive(
            SystemServerWorkspaceBridge.isWorkspaceActive(activity),
        )
        registerControlReceiver(activity.applicationContext)
        registerSystemUiReceiver(activity.applicationContext)
        if (LauncherPackageResolver.isCurrentHome(activity)) {
            notifySystemUiLauncherReady(activity.applicationContext)
        }
    }

    private fun notifySystemUiLauncherReady(context: Context) {
        runCatching {
            context.sendBroadcast(TriggerBroadcastContract.launcherReadyIntent(context))
        }.onFailure { error ->
            NeXtepLog.warn("launcher_runtime", "Unable to notify SystemUI launcher readiness", error)
        }
    }

    private fun registerSystemUiReceiver(context: Context) {
        if (!systemUiReceiverRegistered.compareAndSet(false, true)) return
        try {
            context.registerReceiver(
                systemUiControlReceiver,
                IntentFilter(TriggerBroadcastContract.ACTION_SYSTEMUI_SET_WORKSPACE),
                null,
                Handler(Looper.getMainLooper()),
                Context.RECEIVER_EXPORTED,
            )
            NeXtepLog.info("launcher_runtime", "SystemUI recovery receiver ready")
        } catch (error: Throwable) {
            systemUiReceiverRegistered.set(false)
            NeXtepLog.error("launcher_runtime", "SystemUI recovery receiver failed", error)
        }
    }

    fun redirectActivityLaunch(
        context: Context,
        intent: Intent,
        targetUser: UserHandle,
    ): Boolean {
        if (!LauncherTransformController.isActive() ||
            !LauncherPackageResolver.isCurrentHome(context)
        ) {
            return false
        }
        val component = intent.component ?: context.packageManager.resolveActivity(intent, 0)
            ?.activityInfo
            ?.let { android.content.ComponentName(it.packageName, it.name) }
            ?: return false
        if (component.packageName == context.packageName) return false

        return runCatching {
            context.sendBroadcast(
                TriggerBroadcastContract.launcherAppRequestIntent(
                    context,
                    Intent(intent)
                        .setComponent(component)
                        .putExtra(TriggerBroadcastContract.EXTRA_TARGET_USER, targetUser)
                        .putExtra(
                            TriggerBroadcastContract.EXTRA_TARGET_USER_ID,
                            TaskInfoCompat.userIdentifier(targetUser) ?: 0,
                        )
                        .putExtra(
                            TriggerBroadcastContract.EXTRA_BYPASS_MULTI_APP_CHOOSER,
                            true,
                        ),
                ),
            )
            NeXtepLog.info(
                "launcher_redirect",
                "Redirected component=$component to SystemUI workspace",
            )
            true
        }.getOrElse { error ->
            NeXtepLog.warn("launcher_redirect", "Redirect failed component=$component", error)
            false
        }
    }

    private fun registerControlReceiver(context: Context) {
        if (!receiverRegistered.compareAndSet(false, true)) return

        val filter = IntentFilter().apply {
            addAction(TriggerBroadcastContract.ACTION_TOGGLE_WORKSPACE)
            addAction(TriggerBroadcastContract.ACTION_SET_WORKSPACE)
            addAction(TriggerBroadcastContract.ACTION_QUERY_WORKSPACE)
        }
        try {
            context.registerReceiver(
                controlReceiver,
                filter,
                TriggerBroadcastContract.CONTROL_PERMISSION,
                Handler(Looper.getMainLooper()),
                Context.RECEIVER_EXPORTED,
            )
            NeXtepLog.info("launcher_runtime", "Control receiver ready package=${context.packageName}")
        } catch (error: Throwable) {
            receiverRegistered.set(false)
            NeXtepLog.error("launcher_runtime", "Control receiver registration failed", error)
        }
    }

    private val controlReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!isOrderedBroadcast) return
            if (!SystemServerWorkspaceBridge.isWorkspaceActive(context) &&
                LauncherTransformController.isActive()
            ) {
                LauncherTransformController.setActive(false)
            }
            val handled = when (intent.action) {
                TriggerBroadcastContract.ACTION_TOGGLE_WORKSPACE ->
                    LauncherTransformController.toggle()
                TriggerBroadcastContract.ACTION_SET_WORKSPACE -> {
                    val active = intent.getBooleanExtra(TriggerBroadcastContract.EXTRA_REQUESTED_ACTIVE, false)
                    val systemActive = SystemServerWorkspaceBridge.isWorkspaceActive(context)
                    if (!WorkspaceRecoveryPolicy.shouldApply(active,
                            intent.getBooleanExtra(TriggerBroadcastContract.EXTRA_ONLY_IF_INACTIVE, false),
                            systemActive)) true
                    else LauncherTransformController.setActive(active && systemActive)
                }
                TriggerBroadcastContract.ACTION_QUERY_WORKSPACE -> true
                else -> false
            }
            if (!handled) return

            resultCode = TriggerBroadcastContract.RESULT_HANDLED
            val response = getResultExtras(true) ?: Bundle()
            response.apply {
                putBoolean(
                    TriggerBroadcastContract.EXTRA_ACTIVE,
                    LauncherTransformController.isActive(),
                )
                putString(TriggerBroadcastContract.EXTRA_HOME_PACKAGE, context.packageName)
                putInt(
                    TriggerBroadcastContract.EXTRA_RUNTIME_PROTOCOL_VERSION,
                    TriggerBroadcastContract.RUNTIME_PROTOCOL_VERSION,
                )
            }
            setResultExtras(response)
        }
    }

    private val systemUiControlReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != TriggerBroadcastContract.ACTION_SYSTEMUI_SET_WORKSPACE) return
            val identity = intent.getParcelableExtra(
                TriggerBroadcastContract.EXTRA_SYSTEMUI_IDENTITY,
                PendingIntent::class.java,
            )
            if (identity?.creatorPackage != TriggerBroadcastContract.SYSTEM_UI_PACKAGE) {
                NeXtepLog.warn(
                    "launcher_runtime",
                    "Rejected SystemUI recovery sender=${identity?.creatorPackage}",
                )
                return
            }
            LauncherTransformController.setActive(
                intent.getBooleanExtra(
                    TriggerBroadcastContract.EXTRA_REQUESTED_ACTIVE,
                    false,
                ) && SystemServerWorkspaceBridge.isWorkspaceActive(context),
            )
            if (isOrderedBroadcast) {
                resultCode = TriggerBroadcastContract.RESULT_HANDLED
            }
        }
    }
}
