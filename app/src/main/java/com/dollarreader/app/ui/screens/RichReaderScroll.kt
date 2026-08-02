package com.dollarreader.app.ui.screens

import android.view.View
import android.webkit.WebView

internal fun webProgress(view: View): Float {
    val webView = view as? WebView ?: return 0f
    val contentHeight = (webView.contentHeight * webView.scale).toInt()
    val maximum = (contentHeight - webView.height).coerceAtLeast(1)
    return (webView.scrollY.toFloat() / maximum.toFloat()).coerceIn(0f, 1f)
}
