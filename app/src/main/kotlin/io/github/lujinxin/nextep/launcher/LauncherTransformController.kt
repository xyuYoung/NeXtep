package io.github.lujinxin.nextep.launcher

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.KeyguardManager
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.Display
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import io.github.lujinxin.nextep.logging.NeXtepLog
import io.github.lujinxin.nextep.systemui.SystemDialogLayoutController
import io.github.lujinxin.nextep.workspace.SystemServerWorkspaceBridge
import io.github.lujinxin.nextep.workspace.WorkspaceGeometry
import io.github.lujinxin.nextep.workspace.WorkspaceTransitionSpec
import java.lang.ref.WeakReference
import java.util.WeakHashMap

object LauncherTransformController {
    private data class OriginalTransform(
        val pivotX: Float,
        val pivotY: Float,
        val scaleX: Float,
        val scaleY: Float,
        val translationX: Float,
        val translationY: Float,
        val animationMatrix: Matrix?,
        val clipBounds: Rect?,
        val outlineProvider: ViewOutlineProvider?,
        val clipToOutline: Boolean,
        val screenX: Int,
        val screenY: Int,
    )

    private var decorReference = WeakReference<View>(null)
    private var original: OriginalTransform? = null
    @Volatile private var appliedTransform: TouchCoordinateMapper.Transform? = null
    private val positionAnimators = WeakHashMap<View, ValueAnimator>()
    private val positionTargets = WeakHashMap<View, TouchCoordinateMapper.Transform>()
    @Volatile private var active = false
    private var applyGeneration = 0
    private var entryAnimationUntil = 0L
    private val handler = Handler(Looper.getMainLooper())
    private val layoutListener = View.OnLayoutChangeListener { view, left, top, right, bottom,
        oldLeft, oldTop, oldRight, oldBottom ->
        if ((right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) &&
            (active || positionAnimators.containsKey(view))
        ) {
            releaseViewportTransform(view)
            entryAnimationUntil = 0L
            if (active) scheduleApply()
        }
    }

    fun attach(view: View) {
        ensureMainThread()
        if (!LauncherPackageResolver.isWorkspaceSurface(view.context)) return

        val decor = view.rootView ?: view
        if (decor.width <= 0 || decor.height <= 0) {
            decor.post { attach(decor) }
            return
        }

        if (decorReference.get() !== decor) {
            decorReference.get()?.removeOnLayoutChangeListener(layoutListener)
            restoreCurrentDecor()
            decorReference = WeakReference(decor)
            original = captureOriginal(decor)
            decor.addOnLayoutChangeListener(layoutListener)
        }

        if (active) scheduleApply()
    }

    fun attach(activity: android.app.Activity) {
        activity.window?.decorView?.let(::attach)
    }

    fun prepareForConfigurationChange(activity: android.app.Activity) {
        ensureMainThread()
        val decor = decorReference.get() ?: return
        if (!active || activity.window?.peekDecorView() !== decor) return
        releaseViewportTransform(decor)
        entryAnimationUntil = 0L
        scheduleApply()
    }

    fun toggle(): Boolean = setActive(!active)

    fun setActive(requestedActive: Boolean): Boolean {
        ensureMainThread()
        val changed = active != requestedActive
        if (!changed && !active && positionAnimators.isNotEmpty()) {
            val root = decorReference.get()
            if (root != null && canAnimate(root)) return true
        }
        active = requestedActive
        if (active && changed) entryAnimationUntil = SystemClock.uptimeMillis() + WorkspaceTransitionSpec.DURATION_MS
        if (!active) {
            SystemDialogLayoutController.setGeometry(null)
        }
        applyGeneration += 1
        val decor = decorReference.get()
        if (decor == null) {
            NeXtepLog.info(
                "launcher_transform",
                "active=$active pending=true; waiting for workspace surface DecorView attach",
            )
            return true
        }
        if (active) scheduleApply() else restoreCurrentDecor(animate = changed && canAnimate(decor))
        NeXtepLog.info("launcher_transform", "active=$active decor=${decor.javaClass.name}")
        return true
    }

    fun isActive(): Boolean = active

    fun mapToContent(event: MotionEvent?): TouchCoordinateMapper.Transform? {
        val transform = appliedTransform
        return transform?.takeIf { TouchCoordinateMapper.toContent(event, it) }
    }

    fun mapToScreen(event: MotionEvent?, transform: TouchCoordinateMapper.Transform?) {
        TouchCoordinateMapper.toScreen(event, transform)
    }

    private fun applyTo(decor: View) {
        if (!decor.isAttachedToWindow) {
            releaseViewportTransform(decor)
            return
        }
        val display = decor.display
        val displayMetrics = decor.resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels
        val geometry = SystemServerWorkspaceBridge.activeGeometry(decor.context) ?: WorkspaceGeometry.forDisplay(
            screenWidth,
            screenHeight,
            SystemServerWorkspaceBridge.sidebarSide(decor.context),
            density = displayMetrics.density,
            displayRotation = decor.display?.rotation ?: 0,
        )
        if (display == null || display.displayId != Display.DEFAULT_DISPLAY ||
            !LauncherAnimationPolicy.matchesViewport(
                geometry.displayRotation, display.rotation, geometry.screenWidth, geometry.screenHeight,
                decor.width, decor.height,
            )
        ) {
            releaseViewportTransform(decor)
            entryAnimationUntil = 0L
            return
        }
        if (appliedTransform == null && !positionAnimators.containsKey(decor)) {
            original = captureOriginal(decor)
        }
        SystemDialogLayoutController.setGeometry(geometry)
        val sourceBounds = Rect(
            geometry.availableLeft, geometry.availableTop,
            geometry.availableRight, geometry.availableBottom,
        )
        if (sourceBounds.width() <= 0 || sourceBounds.height() <= 0) return
        val transform = TouchCoordinateMapper.Transform(
            scaleX = geometry.contentScale,
            scaleY = geometry.contentScale,
            translationX = geometry.contentTranslationX,
            translationY = geometry.contentTranslationY,
        )

        val current = appliedTransform
        if (positionAnimators[decor]?.isRunning == true && positionTargets[decor] == transform) return
        val entering = current == null && SystemClock.uptimeMillis() <= entryAnimationUntil
        if ((entering || (current != null && current != transform)) && canAnimate(decor)) {
            animateTransform(decor, current ?: nativeTransform(original), transform, sourceBounds)
        } else {
            cancelPositionAnimation(decor)
            decor.clipBounds = sourceBounds
            writeTransform(decor, transform)
        }
        applyDecorCornerRadius(decor, sourceBounds, transform.scaleX)
    }

    private fun applyDecorCornerRadius(decor: View, sourceBounds: Rect, scale: Float) {
        val cornerDp = SystemServerWorkspaceBridge.windowCornerRadiusDp(decor.context)
        if (cornerDp <= 0 || scale <= 0f) {
            val saved = original
            decor.outlineProvider = saved?.outlineProvider ?: ViewOutlineProvider.BACKGROUND
            decor.clipToOutline = saved?.clipToOutline ?: false
            decor.invalidateOutline()
            return
        }
        val visualPx = cornerDp * decor.resources.displayMetrics.density
        val preScaleRadius = visualPx / scale
        val clipRect = Rect(sourceBounds)
        decor.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(
                    clipRect.left,
                    clipRect.top,
                    clipRect.right,
                    clipRect.bottom,
                    preScaleRadius,
                )
            }
        }
        decor.clipToOutline = true
        decor.invalidateOutline()
    }

    private fun nativeTransform(saved: OriginalTransform?): TouchCoordinateMapper.Transform {
        if (saved == null) return TouchCoordinateMapper.Transform(1f, 1f, 0f, 0f)
        return TouchCoordinateMapper.Transform(
            saved.scaleX, saved.scaleY,
            saved.translationX + saved.pivotX * (1f - saved.scaleX),
            saved.translationY + saved.pivotY * (1f - saved.scaleY),
        )
    }

    private fun animateTransform(
        decor: View,
        from: TouchCoordinateMapper.Transform,
        to: TouchCoordinateMapper.Transform,
        targetClip: Rect?,
        onFinished: () -> Unit = {},
    ) {
        cancelPositionAnimation(decor)
        positionTargets[decor] = to
        val fullBounds = Rect(0, 0, decor.width, decor.height)
        val startClip = decor.clipBounds?.let(::Rect) ?: fullBounds
        val endClip = targetClip ?: fullBounds
        val animator = ValueAnimator.ofFloat(0f, 1f)
        val startRotation = decor.display?.rotation
        animator.duration = WorkspaceTransitionSpec.DURATION_MS
        animator.interpolator = WorkspaceTransitionSpec.interpolator
        animator.addUpdateListener {
            if (!decor.isAttachedToWindow || decor.display?.rotation != startRotation) {
                releaseViewportTransform(decor)
                entryAnimationUntil = 0L
                if (active) scheduleApply()
                return@addUpdateListener
            }
            val progress = it.animatedValue as Float
            fun lerp(start: Float, end: Float) = start + (end - start) * progress
            decor.clipBounds = Rect(
                lerp(startClip.left.toFloat(), endClip.left.toFloat()).toInt(),
                lerp(startClip.top.toFloat(), endClip.top.toFloat()).toInt(),
                lerp(startClip.right.toFloat(), endClip.right.toFloat()).toInt(),
                lerp(startClip.bottom.toFloat(), endClip.bottom.toFloat()).toInt(),
            )
            writeTransform(
                decor,
                TouchCoordinateMapper.Transform(
                    lerp(from.scaleX, to.scaleX), lerp(from.scaleY, to.scaleY),
                    lerp(from.translationX, to.translationX), lerp(from.translationY, to.translationY),
                ),
            )
        }
        animator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                if (positionAnimators[decor] !== animator) return
                positionAnimators.remove(decor)
                positionTargets.remove(decor)
                decor.clipBounds = targetClip
                writeTransform(decor, to)
                onFinished()
            }
        })
        positionAnimators[decor] = animator
        animator.start()
    }

    private fun cancelPositionAnimation(decor: View) {
        positionTargets.remove(decor)
        positionAnimators.remove(decor)?.cancel()
    }

    private fun releaseViewportTransform(decor: View) {
        val animating = positionAnimators.containsKey(decor)
        cancelPositionAnimation(decor)
        if (appliedTransform != null || animating) original?.let { restore(decor, it) }
        appliedTransform = null
        SystemDialogLayoutController.setGeometry(null)
    }

    private fun canAnimate(view: View): Boolean = runCatching {
        view.isAttachedToWindow && ValueAnimator.areAnimatorsEnabled() &&
            view.context.getSystemService(PowerManager::class.java)?.isInteractive == true &&
            view.context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == false
    }.getOrDefault(false)

    private fun writeTransform(decor: View, transform: TouchCoordinateMapper.Transform) {
        decor.pivotX = 0f
        decor.pivotY = 0f
        decor.scaleX = transform.scaleX
        decor.scaleY = transform.scaleY
        decor.translationX = transform.translationX
        decor.translationY = transform.translationY
        appliedTransform = transform
    }

    private fun scheduleApply() {
        val generation = ++applyGeneration
        REAPPLY_DELAYS_MS.forEach { delay ->
            handler.postDelayed({
                if (!active || generation != applyGeneration) return@postDelayed
                decorReference.get()?.let(::applyTo)
            }, delay)
        }
    }

    private fun restoreCurrentDecor(animate: Boolean = false) {
        val decor = decorReference.get()
        val saved = original
        if (decor != null && saved != null &&
            (appliedTransform != null || positionAnimators.containsKey(decor))
        ) {
            val current = appliedTransform
            if (animate && current != null && canAnimate(decor)) {
                animateTransform(decor, current, nativeTransform(saved), saved.clipBounds) {
                    restore(decor, saved)
                    appliedTransform = null
                }
            } else {
                cancelPositionAnimation(decor)
                restore(decor, saved)
                appliedTransform = null
            }
        } else appliedTransform = null
    }

    private fun captureOriginal(view: View): OriginalTransform {
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        return OriginalTransform(
            pivotX = view.pivotX,
            pivotY = view.pivotY,
            scaleX = view.scaleX,
            scaleY = view.scaleY,
            translationX = view.translationX,
            translationY = view.translationY,
            animationMatrix = view.animationMatrix?.let(::Matrix),
            clipBounds = view.clipBounds?.let(::Rect),
            outlineProvider = view.outlineProvider,
            clipToOutline = view.clipToOutline,
            screenX = location[0],
            screenY = location[1],
        )
    }

    private fun restore(view: View, saved: OriginalTransform) {
        view.pivotX = saved.pivotX
        view.pivotY = saved.pivotY
        view.scaleX = saved.scaleX
        view.scaleY = saved.scaleY
        view.translationX = saved.translationX
        view.translationY = saved.translationY
        view.animationMatrix = saved.animationMatrix
        view.clipBounds = saved.clipBounds
        view.outlineProvider = saved.outlineProvider
        view.clipToOutline = saved.clipToOutline
        view.invalidateOutline()
    }

    private fun ensureMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "Launcher transforms must run on the main thread"
        }
    }

    private val REAPPLY_DELAYS_MS = longArrayOf(0L, 120L, 300L, 600L, 1_200L, 2_000L)
}
