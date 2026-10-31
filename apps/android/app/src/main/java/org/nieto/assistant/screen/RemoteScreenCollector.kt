package org.nieto.assistant.screen

import android.graphics.Rect
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.text.InputType
import android.view.accessibility.AccessibilityNodeInfo
import org.nieto.assistant.flow.Box
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class CollectedScreen(val context: ScreenContext, val redactions: List<Box>, val windowId: Int, val imageAllowed: Boolean = true)

object RemoteScreenCollector {
    private val localFingerprint = LocalScreenFingerprint()
    fun collect(root: AccessibilityNodeInfo, width: Int, height: Int, now: Long, revision: Long = 0,
        navigation: ScreenNavigation = ScreenNavigation()): CollectedScreen {
        val pkg = root.packageName?.toString().orEmpty()
        val tree = ScreenTreeCollector.collect(AndroidScreenNode(root), width, height, navigation)
        val blocked = ScreenPolicy.blockedPackage(pkg)
        val digest = localFingerprint.begin("$pkg|${root.windowId}|$width|$height|$revision|$navigation")
        digest.add(tree.structure)
        digest.add("$blocked|${tree.imageAllowed}|" + tree.controls.joinToString { "${it.id}:${it.label}:${it.role}:${it.bounds}:${it.redacted}" } + tree.redactions.joinToString())
        return CollectedScreen(ScreenContext(UUID.randomUUID().toString(), pkg, width, height,
            if (blocked) emptyList() else tree.controls, now, digest.finish(), blocked = blocked, navigation = navigation),
            tree.redactions, root.windowId, !blocked && tree.imageAllowed)
    }
}

private class AndroidScreenNode(private val node: AccessibilityNodeInfo) : ScreenTreeNode {
    override val visible get() = node.isVisibleToUser
    override val enabled get() = node.isEnabled
    override val editable get() = node.isEditable || node.className?.toString()?.contains("EditText") == true
    override val password: Boolean get() {
        val variation = node.inputType and InputType.TYPE_MASK_VARIATION
        val inputClass = node.inputType and InputType.TYPE_MASK_CLASS
        return node.isPassword || (editable && ((inputClass == InputType.TYPE_CLASS_TEXT && variation in setOf(
            InputType.TYPE_TEXT_VARIATION_PASSWORD, InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)) ||
            (inputClass == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD)))
    }
    override val dataSensitive get() = Build.VERSION.SDK_INT >= 34 && node.isAccessibilityDataSensitive
    override val clickable get() = node.isClickable
    override val longClickable get() = node.isLongClickable
    override val checkable get() = node.isCheckable
    override val scrollable get() = node.isScrollable
    override val heading get() = Build.VERSION.SDK_INT >= 28 && node.isHeading
    override val resourceName get() = node.viewIdResourceName.orEmpty()
    override val className get() = node.className?.toString().orEmpty()
    override val bounds: Box get() = Rect().also { node.getBoundsInScreen(it) }.let { Box(it.left, it.top, it.right, it.bottom) }
    override val childCount get() = node.childCount
    override fun text() = node.text
    override fun description() = node.contentDescription
    override fun child(index: Int) = node.getChild(index)?.let { AndroidScreenNode(it) }
    @Suppress("DEPRECATION") override fun close() = node.recycle()
}

fun ScreenContext.toWire(): JSONObject = JSONObject().put("id", id).put("packageName", packageName)
    .put("width", width).put("height", height).put("controls", JSONArray().apply {
        controls.forEach { control -> put(JSONObject().put("id", control.id).put("label", control.label).put("role", control.role)
            .put("bounds", JSONArray(listOf(control.bounds.left, control.bounds.top, control.bounds.right, control.bounds.bottom)))
            .apply { if (control.redacted) put("redacted", true) }) }
    }).put("navigation", JSONObject().put("home", navigation.home).put("back", navigation.back)
        .put("isLauncher", navigation.isLauncher).put("installedApps", JSONArray().apply {
            navigation.installedApps.take(6).forEach { app ->
                ScreenNavigationPolicy.installedApp(app.packageName, app.label)?.let { safe ->
                    put(JSONObject().put("packageName", safe.packageName).put("label", safe.label))
                }
            }
        })).apply { image?.let { put("image", JSONObject().put("mimeType", "image/jpeg").put("data", it)) } }

/** Reads system navigation and the default launcher; never queries an installed-app inventory. */
object ScreenNavigationCollector {
    @Suppress("DEPRECATION")
    fun collect(context: Context, observedPackage: String): ScreenNavigation {
        val resourceMode = runCatching {
            val resource = context.resources.getIdentifier("config_navBarInteractionMode", "integer", "android")
            if (resource == 0) null else context.resources.getInteger(resource)
        }.getOrNull()
        val secureMode = runCatching {
            Settings.Secure.getString(context.contentResolver, "navigation_mode")?.let { it.toIntOrNull() ?: -1 }
        }.getOrNull()
        val method = ScreenNavigationPolicy.method(resourceMode, secureMode)
        val manager = context.packageManager
        val launcher = runCatching {
            manager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo?.packageName
        }.getOrNull()
        return ScreenNavigation(home = method, back = method,
            isLauncher = !observedPackage.isBlank() && launcher == observedPackage)
    }
}

interface ScreenCaptureBridge {
    fun capture(callback: (ScreenContext?) -> Unit)
}
