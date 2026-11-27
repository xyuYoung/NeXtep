package io.github.lujinxin.nextep.launcher

import android.app.Activity
import android.app.Instrumentation
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.res.Configuration
import android.os.Process
import android.os.UserHandle
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.lujinxin.nextep.logging.NeXtepLog
import io.github.lujinxin.nextep.systemui.SystemDialogLayoutController
import io.github.lujinxin.nextep.xposed.HookGuard
import java.lang.reflect.Method

object ColorOsLauncherHook {
    fun install(module: XposedModule, packageName: String, @Suppress("UNUSED_PARAMETER") classLoader: ClassLoader) {
        HookGuard.run("launcher_dialog_layout") {
            Class.forName("android.view.WindowManagerGlobal").declaredMethods
                .filter { method ->
                    method.name in setOf("addView", "updateViewLayout") &&
                        method.parameterTypes.firstOrNull() == View::class.java
                }
                .forEach { method ->
                    method.isAccessible = true
                    module.hook(method).intercept(object : XposedInterface.Hooker {
                        override fun intercept(chain: XposedInterface.Chain): Any? {
                            val view = chain.getArg(0) as? View
                            val params = chain.args.getOrNull(1) as? WindowManager.LayoutParams
                            if (view != null && params != null &&
                                LauncherPackageResolver.isCurrentHome(view.context)
                            ) {
                                HookGuard.run("launcher_dialog_layout_apply") {
                                    // Dialogs have their own windows and do not inherit the
                                    // Activity decor transform. Resize the actual window so
                                    // its text, buttons and native input share the same bounds.
                                    SystemDialogLayoutController.beforeLayout(
                                        view, params, includeApplicationDialogs = true,
                                    )
                                }
                            }
                            return chain.proceed()
                        }
                    })
                }
        }
        HookGuard.run("launcher_hooks") {
            val onPostResume = Activity::class.java.getDeclaredMethod("onPostResume")
            val dispatchTouchEvent = Activity::class.java.getDeclaredMethod(
                "dispatchTouchEvent",
                MotionEvent::class.java,
            )
            onPostResume.isAccessible = true
            dispatchTouchEvent.isAccessible = true
            module.hook(onPostResume).intercept(LauncherLifecycleHooker(module))
            module.hook(dispatchTouchEvent).intercept(LauncherTouchHooker())
            Instrumentation::class.java.declaredMethods
                .filter { method ->
                    method.name == "execStartActivity" &&
                        method.parameterTypes.any { it == Intent::class.java }
                }
                .forEach { method ->
                    method.isAccessible = true
                    module.hook(method).intercept(LauncherStartActivityHooker())
                }
            HookGuard.run("launcher_apps_start_hook") {
                LauncherApps::class.java.declaredMethods
                    .filter { method ->
                        method.name == "startMainActivity" &&
                            method.parameterTypes.size >= 2 &&
                            method.parameterTypes[0] == ComponentName::class.java &&
                            method.parameterTypes[1] == UserHandle::class.java
                    }
                    .forEach { method ->
                        method.isAccessible = true
                        module.hook(method).intercept(LauncherAppsStartHooker())
                    }
            }
            NeXtepLog.info("launcher_hooks", "Generic AOSP launcher hooks installed for $packageName")
        }
    }
}

class LauncherStartActivityHooker : XposedInterface.Hooker {
    override fun intercept(chain: XposedInterface.Chain): Any? {
        val intent = chain.args.firstOrNull { it is Intent } as? Intent
        val context = chain.args.firstOrNull { it is Context } as? Context
        val targetUser = chain.args.firstOrNull { it is UserHandle } as? UserHandle
            ?: Process.myUserHandle()
        if (intent != null && context != null &&
            LauncherRuntime.redirectActivityLaunch(context, intent, targetUser)
        ) {
            return null
        }
        return chain.proceed()
    }
}

class LauncherAppsStartHooker : XposedInterface.Hooker {
    override fun intercept(chain: XposedInterface.Chain): Any? {
        val component = chain.args.getOrNull(0) as? ComponentName
        val targetUser = chain.args.getOrNull(1) as? UserHandle ?: Process.myUserHandle()
        val context = resolveContext(chain.thisObject) ?: LauncherRuntime.applicationContextOrNull()
        if (component != null && context != null) {
            val intent = Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            if (LauncherRuntime.redirectActivityLaunch(context, intent, targetUser)) {
                return null
            }
        }
        return chain.proceed()
    }

    private fun resolveContext(instance: Any?): Context? = runCatching {
        instance ?: return@runCatching null
        generateSequence(instance.javaClass as Class<*>?) { it.superclass }
            .mapNotNull { runCatching { it.getDeclaredField("mContext") }.getOrNull() }
            .firstOrNull()
            ?.apply { isAccessible = true }
            ?.get(instance) as? Context
    }.getOrNull()
}

class LauncherLifecycleHooker(private val module: XposedModule) : XposedInterface.Hooker {
    private val configurationMethods = mutableSetOf<Method>()

    override fun intercept(chain: XposedInterface.Chain): Any? {
        val activity = chain.thisObject as? Activity
        val result = chain.proceed()
        if (activity != null) {
            HookGuard.run("launcher_configuration_install") {
                installConfigurationHook(activity)
            }
            HookGuard.run("launcher_lifecycle") {
                LauncherRuntime.onHomeResumed(activity)
            }
        }
        return result
    }

    private fun installConfigurationHook(activity: Activity) {
        if (!LauncherPackageResolver.isWorkspaceSurface(activity)) return
        val method = activity.javaClass.getMethod("onConfigurationChanged", Configuration::class.java)
        if (method in configurationMethods) return
        method.isAccessible = true
        module.hook(method).intercept(object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                (chain.thisObject as? Activity)?.let { target ->
                    HookGuard.run("launcher_configuration") {
                        LauncherTransformController.prepareForConfigurationChange(target)
                    }
                }
                return chain.proceed()
            }
        })
        configurationMethods += method
    }
}

class LauncherTouchHooker : XposedInterface.Hooker {
    override fun intercept(chain: XposedInterface.Chain): Any? {
        val event = chain.getArg(0) as? MotionEvent
        val transform = LauncherTransformController.mapToContent(event)
        return try {
            chain.proceed()
        } finally {
            LauncherTransformController.mapToScreen(event, transform)
        }
    }
}
