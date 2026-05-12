package com.example.autochat.ui.phone.adapter.com.example.autochat.tts

object TtsTextCleaner {
    fun clean(text: String): String {
        var result = text

        // Xóa <think>...</think>
        result = result.replace(Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL), "")

        // Xóa LaTeX block: $$...$$
        result = result.replace(Regex("\\$\\$.*?\\$\\$", RegexOption.DOT_MATCHES_ALL), "")

        // Xóa LaTeX inline: $...$
        result = result.replace(Regex("\\$[^$]+\\$"), "")

        // Xóa LaTeX \[...\] và \(...\)
        result = result.replace(Regex("\\\\\\[.*?\\\\\\]", RegexOption.DOT_MATCHES_ALL), "")
        result = result.replace(Regex("\\\\\\(.*?\\\\\\)", RegexOption.DOT_MATCHES_ALL), "")

        // Xóa code block ```...```
        result = result.replace(Regex("```.*?```", RegexOption.DOT_MATCHES_ALL), " [code block] ")

        // Xóa inline code `...`
        result = result.replace(Regex("`[^`]+`"), "")

        // Xóa markdown heading ### ## #
        result = result.replace(Regex("^#{1,6}\\s*", RegexOption.MULTILINE), "")

        // Xóa bold/italic **text** *text* __text__ _text_
        result = result.replace(Regex("\\*{1,3}(.+?)\\*{1,3}"), "$1")
        result = result.replace(Regex("_{1,3}(.+?)_{1,3}"), "$1")

        // Xóa link markdown [text](url) → giữ text
        result = result.replace(Regex("\\[([^\\]]+)\\]\\([^)]+\\)"), "$1")

        // Xóa image ![alt](url)
        result = result.replace(Regex("!\\[[^\\]]*\\]\\([^)]+\\)"), "")

        // Xóa blockquote >
        result = result.replace(Regex("^>+\\s*", RegexOption.MULTILINE), "")

        // Xóa horizontal rule --- *** ___
        result = result.replace(Regex("^[-*_]{3,}\\s*$", RegexOption.MULTILINE), "")

        // Xóa bullet list - * +
        result = result.replace(Regex("^[\\-*+]\\s+", RegexOption.MULTILINE), "")

        // Xóa numbered list 1. 2.
//        result = result.replace(Regex("^\\d+\\.\\s+", RegexOption.MULTILINE), "")

        // Xóa HTML tags <br> <b> etc
        result = result.replace(Regex("<[^>]+>"), "")

        // Xóa URL thô
        result = result.replace(Regex("https?://\\S+"), "")

        // Dọn khoảng trắng thừa
        result = result.replace(Regex("[ \\t]+"), " ")
        result = result.replace(Regex("\\n{3,}"), "\n\n")

        return result.trim()
    }
}