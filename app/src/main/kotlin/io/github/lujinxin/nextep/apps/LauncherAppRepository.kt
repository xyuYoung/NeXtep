package io.github.lujinxin.nextep.apps

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.drawable.Drawable
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import io.github.lujinxin.nextep.R
import io.github.lujinxin.nextep.config.AppLanguage
import io.github.lujinxin.nextep.trigger.TriggerBroadcastContract

/** Shared by SystemUI's app strip and the module's manual-order catalogue. */
class LauncherAppRepository(private val context: Context) {
    data class AppEntry(
        val key: String,
        val component: ComponentName,
        val userId: Int,
        val label: String,
        val icon: Drawable,
        val isClone: Boolean,
    ) {
        fun launchIntent(): Intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(TriggerBroadcastContract.EXTRA_TARGET_USER, userHandle(userId))
            .putExtra(TriggerBroadcastContract.EXTRA_TARGET_USER_ID, userId)
            .putExtra(TriggerBroadcastContract.EXTRA_BYPASS_MULTI_APP_CHOOSER, true)
    }

    private val packageManager = context.packageManager
    val currentUserId: Int = Process.myUid() / PER_USER_RANGE

    fun load(includeProfiles: Boolean = true): List<AppEntry> {
        val entries = linkedMapOf<String, AppEntry>()
        val primary = query(packageManager)
        primary.forEach { info ->
            entry(info, packageManager, currentUserId, false, null)?.let { entries[it.key] = it }
        }
        if (!includeProfiles) return entries.values.toList()
        val launcherApps = context.getSystemService(LauncherApps::class.java)
        // Standard profiles are queried independently: one inaccessible profile
        // must not discard the primary catalogue or other user profiles.
        runCatching { launcherApps?.profiles.orEmpty() }.getOrDefault(emptyList()).forEach { user ->
            runCatching { launcherApps?.getActivityList(null, user).orEmpty() }
                .getOrDefault(emptyList()).forEach profileActivity@ { activity ->
                    runCatching {
                        val info = activity.activityInfo
                        val userId = info.applicationInfo.uid / PER_USER_RANGE
                        if (userId == currentUserId || !info.exported || !info.enabled ||
                            info.packageName == "com.android.stk"
                        ) return@profileActivity
                        val component = activity.componentName
                        val label = displayLabel(activity.label.toString(), userId, false, null, null)
                        val icon = runCatching { activity.getBadgedIcon(0) }
                            .getOrElse { userBadgedIcon(activity.getIcon(0), packageManager, userId) }
                        val key = appKey(component, userId)
                        entries[key] = AppEntry(key, component, userId, label, icon, false)
                    }
                }
        }
        return entries.values.toList()
    }

    /** Rehydrate small SystemUI metadata records using the installed APK's icon. */
    fun fromMetadata(key: String, component: ComponentName, userId: Int,
        label: String, isClone: Boolean): AppEntry? = runCatching {
        if (userId !in 0..MAX_USER_ID || key.isBlank()) return@runCatching null
        val activity = packageManager.getActivityInfo(component, PackageManager.ComponentInfoFlags.of(0))
        val icon = userBadgedIcon(activity.loadUnbadgedIcon(packageManager), packageManager, userId)
        AppEntry(key, component, userId, label, icon, isClone)
    }.getOrNull()

    fun contextForUser(userId: Int): Context? = if (userId == currentUserId) context else runCatching {
        Context::class.java.getMethod("createContextAsUser", UserHandle::class.java,
            Int::class.javaPrimitiveType!!).invoke(context, userHandle(userId), 0) as Context
    }.getOrNull()

    private fun query(manager: PackageManager): List<ResolveInfo> = manager.queryIntentActivities(
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
        PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong()),
    )

    private fun entry(info: ResolveInfo, manager: PackageManager, userId: Int,
        isClone: Boolean, alias: String?, cloneLabel: String? = null): AppEntry? = runCatching {
        val activity = info.activityInfo ?: return@runCatching null
        if (!activity.exported || !activity.enabled || activity.packageName == "com.android.stk") {
            return@runCatching null
        }
        val component = ComponentName(activity.packageName, activity.name)
        val icon = userBadgedIcon(activity.loadUnbadgedIcon(manager), manager, userId)
        AppEntry(appKey(component, userId), component, userId,
            displayLabel(info.loadLabel(manager).toString(), userId, isClone, alias, cloneLabel),
            icon, isClone)
    }.getOrNull()

    private fun appKey(component: ComponentName, userId: Int): String {
        // Preserve existing primary-app selections and their order verbatim.
        if (userId == currentUserId) return component.flattenToString()
        val serial = runCatching {
            context.getSystemService(UserManager::class.java)?.getSerialNumberForUser(userHandle(userId))
        }.getOrNull()?.takeIf { it >= 0 }
        val profile = serial?.let { "serial:$it" } ?: "id:$userId"
        return "$profile|${component.flattenToString()}"
    }

    private fun displayLabel(label: String, userId: Int, isClone: Boolean,
        alias: String?, cloneLabel: String?): String = when {
        userId == currentUserId -> label
        isClone -> AppLanguage.string(context, R.string.app_clone_label, alias?.takeIf(String::isNotBlank) ?: label, cloneLabel ?: AppLanguage.string(context, R.string.app_clone))
        else -> AppLanguage.string(context, R.string.app_user_label, label, userId)
    }

    // Start with an unbadged icon and let the system apply the target user's
    // badge once. ColorOS already supplies its own clone badge; a second custom
    // overlay would also make SystemUI and the metadata-based settings list differ.
    private fun userBadgedIcon(source: Drawable, manager: PackageManager, userId: Int): Drawable =
        runCatching { manager.getUserBadgedIcon(source, userHandle(userId)) }.getOrDefault(source)

    companion object {
        private const val PER_USER_RANGE = 100_000
        private const val MAX_USER_ID = Int.MAX_VALUE / PER_USER_RANGE

        fun userHandle(userId: Int): UserHandle {
            require(userId in 0..MAX_USER_ID)
            return UserHandle.getUserHandleForUid(userId * PER_USER_RANGE)
        }
    }
}
