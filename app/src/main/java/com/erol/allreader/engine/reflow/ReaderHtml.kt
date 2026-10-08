package com.erol.allreader.engine.reflow

enum class ReaderTheme(val label: String) {
    SYSTEM("Sistem"),
    LIGHT("Açık"),
    DARK("Koyu"),
    SEPIA("Sepya"),
}

/** HTML gövdesini okuyucu temasıyla tam bir belgeye sarar. */
object ReaderHtml {
    private data class Palette(val bg: String, val fg: String, val link: String, val border: String, val code: String)

    private val light = Palette("#ffffff", "#1b1b1b", "#0b57d0", "#c9c9c9", "#f1f1f1")
    private val dark = Palette("#121212", "#e3e3e3", "#8ab4f8", "#444444", "#1f1f1f")
    private val sepia = Palette("#f4ecd8", "#4b3a2a", "#8a4b08", "#cdbf9f", "#eadfc4")

    /** [ReaderTheme.SYSTEM] ise [systemDark] belirler. */
    fun resolve(theme: ReaderTheme, systemDark: Boolean): ReaderTheme = when (theme) {
        ReaderTheme.SYSTEM -> if (systemDark) ReaderTheme.DARK else ReaderTheme.LIGHT
        else -> theme
    }

    fun backgroundColor(theme: ReaderTheme, systemDark: Boolean): Long {
        val p = palette(resolve(theme, systemDark))
        return p.bg.removePrefix("#").toLong(16) or 0xFF000000
    }

    fun wrap(body: String, theme: ReaderTheme, systemDark: Boolean): String {
        val p = palette(resolve(theme, systemDark))
        val scheme = if (resolve(theme, systemDark) == ReaderTheme.DARK) "dark" else "light"
        return """<!DOCTYPE html>
<html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="color-scheme" content="$scheme">
<style>
html { background: ${p.bg}; }
body { background: ${p.bg}; color: ${p.fg}; margin: 0; padding: 16px 18px 48px; line-height: 1.6;
  font-family: sans-serif; overflow-wrap: anywhere; word-wrap: break-word; }
a { color: ${p.link}; }
pre.plain { white-space: pre-wrap; word-wrap: break-word; font-family: sans-serif; margin: 0;
  background: none; padding: 0; border-radius: 0; overflow-x: visible; }
pre, code { background: ${p.code}; border-radius: 4px; }
pre { padding: 10px; overflow-x: auto; }
code { padding: 1px 4px; font-family: monospace; }
pre code { padding: 0; background: none; }
img { max-width: 100%; height: auto; }
table { border-collapse: collapse; max-width: 100%; }
th, td { border: 1px solid ${p.border}; padding: 4px 8px; }
blockquote { margin-left: 0; padding-left: 12px; border-left: 3px solid ${p.border}; opacity: .85; }
u { text-decoration: underline; }
.table-wrap { overflow-x: auto; margin: 8px 0; }
.sheet-table td { white-space: pre-wrap; vertical-align: top; }
.sheet-table td.empty { border: 0; padding: 0; line-height: 1; }
.sheet h2 { margin: 24px 0 8px; }
.sheet-index, .note, .slide-no { opacity: .7; font-size: .85em; }
.slide { border: 1px solid ${p.border}; border-radius: 10px; padding: 8px 14px 12px; margin: 14px 0; }
.slide h2 { margin: 6px 0; }
.notes { margin-top: 10px; padding: 8px 10px; background: ${p.code}; border-radius: 6px; font-size: .9em; }
.missing-img { opacity: .6; font-style: italic; }
hr { border: 0; border-top: 1px solid ${p.border}; }
</style></head><body>$body</body></html>"""
    }

    private fun palette(theme: ReaderTheme): Palette = when (theme) {
        ReaderTheme.DARK -> dark
        ReaderTheme.SEPIA -> sepia
        else -> light
    }
}
