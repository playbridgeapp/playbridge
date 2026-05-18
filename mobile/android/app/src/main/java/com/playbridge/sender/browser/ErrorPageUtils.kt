package com.playbridge.sender.browser

import android.util.Base64

/**
 * Utility to generate user-friendly HTML error pages for browser failures.
 */
object ErrorPageUtils {

    /**
     * Generates a data URI for an HTML error page similar to Chrome/Brave.
     */
    fun generateErrorPage(url: String, statusCode: String, errorDetails: String? = null): String {
        val detailText = errorDetails ?: "The page at $url might be temporarily down or it may have moved permanently to a new web address."
        val codeText = if (statusCode.toIntOrNull() != null) "HTTP ERROR $statusCode" else statusCode

        val html = """
            <!DOCTYPE html>
            <html lang="en">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>Page not available</title>
                <style>
                    body {
                        font-family: 'Segoe UI', Tahoma, sans-serif;
                        background-color: #f7f9fa;
                        color: #3c4043;
                        display: flex;
                        flex-direction: column;
                        align-items: center;
                        justify-content: center;
                        height: 100vh;
                        margin: 0;
                        padding: 20px;
                        box-sizing: border-box;
                        text-align: center;
                    }
                    .container {
                        max-width: 500px;
                    }
                    .icon {
                        font-size: 72px;
                        margin-bottom: 24px;
                        color: #dadce0;
                    }
                    h1 {
                        font-size: 22px;
                        font-weight: 400;
                        margin-bottom: 16px;
                    }
                    p {
                        font-size: 14px;
                        line-height: 1.6;
                        color: #5f6368;
                        margin-bottom: 24px;
                    }
                    .error-code {
                        font-size: 12px;
                        color: #70757a;
                        text-transform: uppercase;
                        border-top: 1px solid #dadce0;
                        padding-top: 16px;
                    }
                    .button {
                        background-color: #1a73e8;
                        color: white;
                        border: none;
                        padding: 10px 24px;
                        border-radius: 4px;
                        font-size: 14px;
                        font-weight: 500;
                        cursor: pointer;
                        text-decoration: none;
                        display: inline-block;
                    }
                    .button:hover {
                        background-color: #185abc;
                    }
                </style>
            </head>
            <body>
                <div class="container">
                    <div class="icon">🔍</div>
                    <h1>This site can't be reached</h1>
                    <p>$detailText</p>
                    <a href="$url" class="button">Reload</a>
                    <div class="error-code">$codeText</div>
                </div>
            </body>
            </html>
        """.trimIndent()

        val encoded = Base64.encodeToString(html.toByteArray(), Base64.NO_WRAP)
        return "data:text/html;base64,$encoded"
    }
}
