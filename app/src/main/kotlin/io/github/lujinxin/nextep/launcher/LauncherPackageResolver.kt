package io.github.lujinxin.nextep.launcher

import android.content.Context
import io.github.lujinxin.nextep.trigger.TriggerBroadcastContract

object LauncherPackageResolver {
    fun isCurrentHome(context: Context): Boolean =
        TriggerBroadcastContract.resolveHomePackage(context) == context.packageName

    fun isWorkspaceSurface(context: Context): Boolean =
        isCurrentHome(context)
}
