/*
 * This file is part of HyperCeiler.
 *
 * HyperCeiler is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 * Copyright (C) 2023-2026 HyperCeiler Contributions
 */
package com.sevtinge.hyperceiler.libhook.rules.aiengine

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Icon
import android.os.Bundle
import androidx.core.net.toUri
import com.sevtinge.hyperceiler.common.utils.PrefsBridge
import com.sevtinge.hyperceiler.libhook.base.BaseHook
import com.sevtinge.hyperceiler.libhook.utils.api.BitmapUtils
import com.sevtinge.hyperceiler.libhook.utils.api.ContextUtils
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.createHook
import org.luckypray.dexkit.query.enums.StringMatchType
import java.lang.reflect.Method
import java.lang.reflect.Modifier

object ChangeBrowserForAIEngine : BaseHook() {

    private const val COPY_WEBSITE_TYPE = 11
    private const val PREF_KEY_BROWSER = "aicr_browser"
    private const val NOTIFICATION_ID = 111
    private const val JUMP_URL_INTENT_ID = "hce.copy_text_jump_app.jump_url"
    private const val KEY_PACKAGE = "copy_text_jump_app_package"
    private const val KEY_NAME = "copy_text_jump_app_name"
    private const val KEY_TITLE = "copy_text_cue_title"

    private var isInstallForAppMethod: Method? = null
    private lateinit var getStartAppPackageMethod: Method
    private var startIntentToAppMethod: Method? = null
    private lateinit var jumpToXiaoMiBrowserMethod: Method
    private lateinit var showNotificationMethod: Method
    private var buildWebsiteIntentMethod: Method? = null
    private var resolveJumpAppMethod: Method? = null

    override fun useDexKit() = true

    override fun initDexKit(): Boolean {
        // void + "clipboard_open" 锁定旧的小米浏览器跳转。4.13 另有一个返回 Intent 的同名用法，靠返回值分开。
        jumpToXiaoMiBrowserMethod = requiredMember("jumpToXiaoMiBrowser") { bridge ->
            bridge.findMethod {
                matcher {
                    addUsingString("clipboard_open")
                    returnType = "void"
                }
            }.single()
        }
        val ownerName = jumpToXiaoMiBrowserMethod.declaringClass.name

        // 3.17.x 是带日志的 (Context, int)。4.13 删掉了这段日志，安装判断仍是同类上的 (Context, int)。
        isInstallForAppMethod = optionalMember("isInstallForApp") { bridge ->
            bridge.findMethod {
                matcher {
                    declaredClass = ownerName
                    returnType = "boolean"
                    paramTypes("android.content.Context", "int")
                    addUsingString("isInstallForApp: ///////////////////")
                }
            }.singleOrNull() ?: bridge.findMethod {
                matcher {
                    declaredClass = ownerName
                    returnType = "boolean"
                    paramTypes("android.content.Context", "int")
                    addUsingString("com.android.browser")
                    addUsingString("com.taobao.taobao")
                }
            }.singleOrNull()
        }

        getStartAppPackageMethod = requiredMember("getStartAppPackage") { bridge ->
            bridge.findMethod {
                matcher {
                    declaredClass = ownerName
                    returnType = "java.lang.String"
                    paramTypes("android.content.Context", "int")
                    addUsingString("com.taobao.taobao")
                    addUsingString("com.eg.android.AlipayGphone")
                }
            }.single()
        }

        startIntentToAppMethod = optionalMember("startIntentToApp") { bridge ->
            bridge.findMethod {
                matcher {
                    declaredClass = ownerName
                    returnType = "void"
                    paramCount = 3
                    paramTypes(null, "java.lang.String", "int")
                    // 4.13 的字面量后面还拼了 sourceApplication，精确匹配会落空。
                    addUsingString("androidamap://route?", StringMatchType.Contains)
                }
            }.singleOrNull()
        }

        // 4.13：get_copy_direct_data 用这个方法生成 Intent，再 toUri 放进动作结果。
        buildWebsiteIntentMethod = optionalMember("buildWebsiteIntent") { bridge ->
            bridge.findMethod {
                matcher {
                    returnType = "android.content.Intent"
                    paramTypes("java.lang.String")
                    addUsingString("clipboard_open")
                }
            }.singleOrNull()
        }

        // 4.13：气泡上的包名和「在某应用中打开」写在这个方法里。
        resolveJumpAppMethod = optionalMember("resolveJumpApp") { bridge ->
            bridge.findMethod {
                matcher {
                    paramCount = 2
                    addUsingString("复制内容不可跳转，无法解析目标应用包名")
                }
            }.singleOrNull()
        }

        showNotificationMethod = requiredMember("showNotification") { bridge ->
            bridge.findMethod {
                matcher { addUsingString("phrase_channel_id") }
            }.single()
        }
        return true
    }

    override fun init() {
        isInstallForAppMethod?.createHook {
            before {
                if ((it.args[1] as? Int) != COPY_WEBSITE_TYPE) return@before
                val ctx = it.args[0] as? Context ?: return@before
                val selected = resolveSelectedPackage(ctx)
                it.result = if (selected != null) {
                    canHandleBrowserIntent(ctx, selected)
                } else {
                    canOpenAnyBrowser(ctx)
                }
            }
        }

        getStartAppPackageMethod.createHook {
            before {
                if ((it.args[1] as? Int) != COPY_WEBSITE_TYPE) return@before
                val ctx = it.args[0] as? Context ?: return@before
                // 用户没选 → 不接管，宿主继续按原值走
                val selected = resolveSelectedPackage(ctx) ?: return@before
                it.result = selected
            }
        }

        if (startIntentToAppMethod != null) {
            // 旧的 IntentActivity 调度。4.13 点击气泡不经过这里，打开动作由下面的 Intent 工厂决定。
            startIntentToAppMethod!!.createHook {
                before {
                    if ((it.args[2] as? Int) != COPY_WEBSITE_TYPE) return@before
                    val ctx = it.args[0] as? Context ?: return@before
                    val url = it.args[1] as? String ?: return@before
                    openInSelectedBrowser(ctx, url)
                    it.result = null
                }
            }
        } else {
            // 4.0.x：宿主已经按 type 内联拆掉，直接接管 jumpToXiaoMiBrowser
            jumpToXiaoMiBrowserMethod.createHook {
                before {
                    val ctx = it.args[0] as? Context ?: return@before
                    val url = it.args[1] as? String ?: return@before
                    openInSelectedBrowser(ctx, url)
                    it.result = null
                }
            }
        }

        showNotificationMethod.createHook {
            after {
                if ((it.args[2] as? Int) != COPY_WEBSITE_TYPE) return@after
                val ctx = it.args[0] as? Context ?: return@after
                refreshNotificationBrowserIcon(ctx)
            }
        }

        // 宿主在返回前已经 setPackage("com.android.browser")。选了浏览器就换上，没选就清掉，点击时由系统解析默认浏览器。
        buildWebsiteIntentMethod?.createHook {
            after {
                val intent = it.result as? Intent ?: return@after
                applyLaunchPackage(intent)
            }
        }

        resolveJumpAppMethod?.createHook {
            after {
                val contextObj = it.args.firstOrNull() ?: return@after
                runCatching { rewriteJumpUrlCue(contextObj) }
            }
        }
    }

    // ==================== 浏览器选择 ====================

    /** 用户显式选定的浏览器包名；null 表示跟随系统默认。 */
    private fun resolveSelectedPackage(context: Context): String? {
        val raw = PrefsBridge.getString(PREF_KEY_BROWSER, "")?.takeIf { it.isNotBlank() } ?: return null
        return raw.takeIf { canHandleBrowserIntent(context, it) }
    }

    /** 系统当前能否打开 http(s)。 */
    private fun canOpenAnyBrowser(context: Context): Boolean {
        return context.packageManager
            .resolveActivity(createBrowserViewIntent(), PackageManager.MATCH_DEFAULT_ONLY) != null
    }

    /** PackageManager 解析的系统默认浏览器包名；不存在返回 null。 */
    private fun resolveSystemDefaultBrowser(context: Context): String? {
        val pkg = context.packageManager
            .resolveActivity(createBrowserViewIntent(), PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName
        return pkg?.takeUnless { it.isBlank() || it == "android" || it == context.packageName }
    }

    /**
     * 4.13 的网页 Intent 工厂没有 Context。选了具体浏览器就换包名；没选就把宿主写死的小米浏览器清掉。
     */
    private fun applyLaunchPackage(intent: Intent) {
        val ctx = hostContext()
        val selected = if (ctx != null) resolveSelectedPackage(ctx) else rawSelectedPackage()
        intent.setPackage(selected)
    }

    /** 气泡只改网页这条。快递单号仍走宿主自己的文案。图标是宿主 drawable 名，第三方包没有对应资源，保持原样。 */
    private fun rewriteJumpUrlCue(contextObj: Any) {
        if (readIntentId(contextObj) != JUMP_URL_INTENT_ID) return
        val ctx = hostContext() ?: return
        val pkg = resolveSelectedPackage(ctx) ?: resolveSystemDefaultBrowser(ctx) ?: return
        val label = applicationLabel(ctx, pkg) ?: pkg
        val owner = resolveJumpAppMethod?.declaringClass ?: return
        val pkgKey = findContextKey(owner, KEY_PACKAGE) ?: return
        val nameKey = findContextKey(owner, KEY_NAME) ?: return
        val titleKey = findContextKey(owner, KEY_TITLE) ?: return
        writeContextValue(contextObj, pkgKey, pkg)
        writeContextValue(contextObj, nameKey, label)
        writeContextValue(contextObj, titleKey, "在${label}中打开")
    }

    private fun hostContext(): Context? {
        return ContextUtils.getContext(ContextUtils.FLAG_CURRENT_APP)
            ?: ContextUtils.getContext(ContextUtils.FLAG_ALL)
    }

    private fun rawSelectedPackage(): String? {
        return PrefsBridge.getString(PREF_KEY_BROWSER, "")?.takeIf { it.isNotBlank() }
    }

    private fun applicationLabel(context: Context, packageName: String): String? {
        val pm = context.packageManager
        val info = runCatching { pm.getApplicationInfo(packageName, 0) }.getOrNull() ?: return null
        return pm.getApplicationLabel(info).toString().ifBlank { null }
    }

    private fun readIntentId(contextObj: Any): String? {
        val getter = contextObj.javaClass.methods.firstOrNull { method ->
            method.parameterCount == 0 && method.returnType.simpleName == "Intention"
        } ?: return null
        val intention = getter.invoke(contextObj) ?: return null
        val idGetter = intention.javaClass.methods.firstOrNull { it.name == "getIntentId" && it.parameterCount == 0 } ?: return null
        return idGetter.invoke(intention) as? String
    }

    private fun findContextKey(owner: Class<*>, keyName: String): Any? {
        for (field in owner.declaredFields) {
            if (!Modifier.isStatic(field.modifiers)) continue
            field.isAccessible = true
            val value = field.get(null) ?: continue
            val name = value.javaClass.declaredFields.firstOrNull { it.type == String::class.java }?.let { nameField ->
                nameField.isAccessible = true
                nameField.get(value) as? String
            }
            if (name == keyName) return value
        }
        return null
    }

    private fun writeContextValue(contextObj: Any, key: Any, value: String) {
        val setter = contextObj.javaClass.methods.firstOrNull { method ->
            method.parameterCount == 2 && method.returnType == Void.TYPE && method.parameterTypes[0].isInstance(key) && method.parameterTypes[1] == Any::class.java
        } ?: return
        setter.invoke(contextObj, key, value)
    }

    private fun openInSelectedBrowser(context: Context, url: String) {
        val target = url.withHttpsIfMissing().toUri()
        val targetPackage = resolveSelectedPackage(context)
        val intent = Intent(Intent.ACTION_VIEW, target).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            targetPackage?.let { setPackage(it) }
        }
        runCatching { context.startActivity(intent) }.onFailure {
            runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW, target).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            }
        }
    }

    @SuppressLint("QueryPermissionsNeeded")
    private fun canHandleBrowserIntent(context: Context, packageName: String): Boolean {
        val intent = createBrowserViewIntent().apply { setPackage(packageName) }
        return context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            .isNotEmpty()
    }

    private fun createBrowserViewIntent(): Intent =
        Intent(Intent.ACTION_VIEW, "http:".toUri()).addCategory(Intent.CATEGORY_BROWSABLE)

    private fun String.withHttpsIfMissing(): String =
        if (startsWith("http://", true) || startsWith("https://", true)) this else "https://$this"

    // ==================== 通知图标替换 ====================

    private fun refreshNotificationBrowserIcon(context: Context) {
        // 用户选了具体浏览器 → 用它的图标；否则用系统默认浏览器的图标
        val targetPackage = resolveSelectedPackage(context) ?: resolveSystemDefaultBrowser(context) ?: return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val notification = nm.activeNotifications.firstOrNull {
            it.id == NOTIFICATION_ID && it.packageName == context.packageName
        }?.notification ?: return

        val pm = context.packageManager
        val info = runCatching { pm.getApplicationInfo(targetPackage, 0) }.getOrNull() ?: return
        val label = pm.getApplicationLabel(info)
        val drawable = runCatching { pm.getApplicationIcon(info) }.getOrNull() ?: return
        val bitmap = if (drawable is BitmapDrawable && drawable.bitmap != null) {
            drawable.bitmap
        } else {
            runCatching { BitmapUtils.drawableToBitmap(drawable) }.getOrNull() ?: return
        }
        val icon = Icon.createWithBitmap(bitmap)

        val focusPics = notification.extras.getBundle("miui.focus.pics") ?: Bundle()
        focusPics.putParcelable("miui.focus.pic_image", icon)
        focusPics.putParcelable("miui.land.pic_image", icon)
        notification.extras.putBundle("miui.focus.pics", focusPics)
        notification.extras.putParcelable("miui.appIcon", icon)
        notification.extras.putCharSequence("android.title", label)
        notification.extras.putCharSequence("android.title.big", label)
        nm.notify(NOTIFICATION_ID, notification)
    }
}
