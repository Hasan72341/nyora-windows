package com.nyora.windows.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import java.awt.Window
import java.io.File

/**
 * Windows-native chrome integration.
 *
 * Reads the OS theme + accent colour, loads the Segoe UI system font, and applies
 * DWM window attributes (dark title bar + Mica system backdrop). Every entry point
 * fails soft — OS-guarded and wrapped in [runCatching] — so the app builds + runs
 * unchanged on the macOS/Linux dev host and on older Windows builds without DWM
 * backdrop support.
 */
object WindowsNative {

    val isWindows: Boolean =
        System.getProperty("os.name").orEmpty().lowercase().contains("win")

    /** Windows accent colour from the registry (DWM\AccentColor, stored little-endian ABGR). */
    val accentColor: Color? by lazy {
        if (!isWindows) return@lazy null
        runCatching {
            val abgr = com.sun.jna.platform.win32.Advapi32Util.registryGetIntValue(
                com.sun.jna.platform.win32.WinReg.HKEY_CURRENT_USER,
                "Software\\Microsoft\\Windows\\DWM",
                "AccentColor",
            )
            Color(
                red   = abgr and 0xFF,
                green = (abgr shr 8) and 0xFF,
                blue  = (abgr shr 16) and 0xFF,
            )
        }.getOrNull()
    }

    /** true = system is in Light mode, false = Dark, null = unknown. */
    val systemLight: Boolean? by lazy {
        if (!isWindows) return@lazy null
        runCatching {
            com.sun.jna.platform.win32.Advapi32Util.registryGetIntValue(
                com.sun.jna.platform.win32.WinReg.HKEY_CURRENT_USER,
                "Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
                "AppsUseLightTheme",
            ) == 1
        }.getOrNull()
    }

    /** Segoe UI loaded from C:\Windows\Fonts (Windows system font), or null elsewhere. */
    private val segoe: FontFamily? by lazy {
        if (!isWindows) return@lazy null
        runCatching {
            val dir = File("C:\\Windows\\Fonts")
            fun face(name: String, weight: FontWeight) =
                File(dir, name).takeIf { it.exists() }
                    ?.let { androidx.compose.ui.text.platform.Font(it, weight) }
            val faces = listOfNotNull(
                face("segoeuil.ttf", FontWeight.Light),
                face("segoeui.ttf", FontWeight.Normal),
                face("segoeuisl.ttf", FontWeight.Medium),
                face("seguisb.ttf", FontWeight.SemiBold),
                face("segoeuib.ttf", FontWeight.Bold),
                face("segoeuib.ttf", FontWeight.ExtraBold),
            )
            if (faces.isEmpty()) null else FontFamily(faces)
        }.getOrNull()
    }

    /** [base] typography with the Segoe family swapped across every style (or [base] as-is). */
    fun typography(base: Typography): Typography {
        val fam = segoe ?: return base
        return base.copy(
            displayLarge   = base.displayLarge.copy(fontFamily = fam),
            displayMedium  = base.displayMedium.copy(fontFamily = fam),
            displaySmall   = base.displaySmall.copy(fontFamily = fam),
            headlineLarge  = base.headlineLarge.copy(fontFamily = fam),
            headlineMedium = base.headlineMedium.copy(fontFamily = fam),
            headlineSmall  = base.headlineSmall.copy(fontFamily = fam),
            titleLarge     = base.titleLarge.copy(fontFamily = fam),
            titleMedium    = base.titleMedium.copy(fontFamily = fam),
            titleSmall     = base.titleSmall.copy(fontFamily = fam),
            bodyLarge      = base.bodyLarge.copy(fontFamily = fam),
            bodyMedium     = base.bodyMedium.copy(fontFamily = fam),
            bodySmall      = base.bodySmall.copy(fontFamily = fam),
            labelLarge     = base.labelLarge.copy(fontFamily = fam),
            labelMedium    = base.labelMedium.copy(fontFamily = fam),
            labelSmall     = base.labelSmall.copy(fontFamily = fam),
        )
    }

    /**
     * Apply DWM window attributes: dark title bar (immersive dark mode, so the native
     * caption matches the app theme) + Mica system backdrop on Windows 11. No-op off
     * Windows / on builds without backdrop support.
     */
    fun applyChrome(window: Window, dark: Boolean) {
        if (!isWindows) return
        runCatching {
            val hwnd = com.sun.jna.platform.win32.WinDef.HWND(
                com.sun.jna.Native.getWindowPointer(window),
            )
            // DWMWA_USE_IMMERSIVE_DARK_MODE = 20
            Dwm.INSTANCE.DwmSetWindowAttribute(
                hwnd, 20, com.sun.jna.ptr.IntByReference(if (dark) 1 else 0), 4,
            )
            // DWMWA_SYSTEMBACKDROP_TYPE = 38 ; DWMSBT_MAINWINDOW (Mica) = 2
            Dwm.INSTANCE.DwmSetWindowAttribute(
                hwnd, 38, com.sun.jna.ptr.IntByReference(2), 4,
            )
        }
    }

    private interface Dwm : com.sun.jna.Library {
        fun DwmSetWindowAttribute(
            hwnd: com.sun.jna.platform.win32.WinDef.HWND,
            attribute: Int,
            value: com.sun.jna.ptr.IntByReference,
            size: Int,
        ): Int

        companion object {
            val INSTANCE: Dwm = com.sun.jna.Native.load("dwmapi", Dwm::class.java)
        }
    }
}
