package com.example.autochat.llm

import android.util.Log
import org.json.JSONObject
import java.io.File

class OnnxTokenizer private constructor(
    private val vocab: Map<String, Long>,
    private val idToToken: Map<Long, String>,
    private val eosTokenId: Long,
    private val bosTokenId: Long,
    private val unknownTokenId: Long,
) {
    var mergesList: List<Pair<String, String>> = emptyList()
    private var _eosTokenIds: Set<Long> = emptySet()

    companion object {
        private const val TAG = "OnnxTokenizer"

        fun fromDir(modelDir: File): OnnxTokenizer {
            val tokenizerJson = listOf(
                File(modelDir, "tokenizer.json"),
                File(modelDir.parentFile ?: modelDir, "tokenizer.json")
            ).firstOrNull { it.exists() }

            val tok = when {
                tokenizerJson != null -> fromTokenizerJson(tokenizerJson)
                File(modelDir, "vocab.txt").exists() -> fromVocabTxt(File(modelDir, "vocab.txt"))
                else -> throw IllegalStateException("Không tìm thấy tokenizer trong ${modelDir.absolutePath}")
            }

            // Đọc tokenizer_config.json để override eos/bos nếu cần
            val tokenizerConfig = listOf(
                File(modelDir, "tokenizer_config.json"),
                File(modelDir.parentFile ?: modelDir, "tokenizer_config.json")
            ).firstOrNull { it.exists() }

            if (tokenizerConfig != null) {
                try {
                    val cfg = JSONObject(tokenizerConfig.readText())
                    val eosToken = cfg.optString("eos_token", "")
                    Log.d(TAG, "tokenizer_config: eos='$eosToken'")
                    if (eosToken.isNotEmpty()) tok.overrideEosToken(eosToken)
                } catch (e: Exception) {
                    Log.w(TAG, "tokenizer_config.json parse failed: ${e.message}")
                }
            }

            // Load merges
            if (tokenizerJson != null) {
                try {
                    val root  = JSONObject(tokenizerJson.readText())
                    val model = root.optJSONObject("model")
                    if (model != null && model.has("merges")) {
                        val mergesArr = model.getJSONArray("merges")
                        tok.mergesList = (0 until mergesArr.length()).map { i ->
                            val parts = mergesArr.getString(i).split(" ")
                            Pair(parts[0], parts[1])
                        }
                        Log.d(TAG, "Loaded ${tok.mergesList.size} BPE merges")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Load merges failed: ${e.message}")
                }
            }

            return tok
        }

        private fun fromTokenizerJson(file: File): OnnxTokenizer {
            val root  = JSONObject(file.readText())
            val model = root.getJSONObject("model")

            val vocabObj  = model.getJSONObject("vocab")
            val vocab     = mutableMapOf<String, Long>()
            val idToToken = mutableMapOf<Long, String>()

            vocabObj.keys().forEach { key ->
                val id = vocabObj.getLong(key)
                vocab[key]    = id
                idToToken[id] = key
            }

            // ✅ Load added_tokens TRƯỚC khi findId để có đầy đủ special tokens
            if (root.has("added_tokens")) {
                val arr = root.getJSONArray("added_tokens")
                for (i in 0 until arr.length()) {
                    val obj     = arr.getJSONObject(i)
                    val id      = obj.getLong("id")
                    val content = obj.getString("content")
                    vocab[content]  = id
                    idToToken[id]   = content
                }
            }

            fun findId(vararg names: String): Long {
                for (name in names) {
                    vocab[name]?.let { return it }
                }
                return -1L
            }

            // ✅ Ưu tiên <|im_end|> cho Qwen2.5
            val eosId = findId(
                "<|im_end|>", "<|endoftext|>", "</s>", "<eos>",
                "<|eot_id|>", "<|end_of_text|>", "<end_of_turn>"
            )
            val bosId = findId("<|im_start|>", "<s>", "<|startoftext|>", "<bos>")
            val unkId = findId("<unk>", "[UNK]")

            Log.d(TAG, "Loaded tokenizer.json: ${vocab.size} tokens, EOS=$eosId BOS=$bosId")

            val tok = OnnxTokenizer(vocab, idToToken, eosId, bosId, unkId)
            tok.buildEosIds()  // ✅ build sau khi vocab + added_tokens đầy đủ
            return tok
        }

        private fun fromVocabTxt(file: File): OnnxTokenizer {
            val vocab     = mutableMapOf<String, Long>()
            val idToToken = mutableMapOf<Long, String>()
            file.readLines().forEachIndexed { idx, line ->
                val token = line.trim()
                vocab[token]            = idx.toLong()
                idToToken[idx.toLong()] = token
            }
            val eosId = vocab["[SEP]"] ?: vocab["</s>"] ?: -1L
            val bosId = vocab["[CLS]"] ?: vocab["<s>"]  ?: -1L
            val unkId = vocab["[UNK]"] ?: 0L
            Log.d(TAG, "Loaded vocab.txt: ${vocab.size} tokens")
            val tok = OnnxTokenizer(vocab, idToToken, eosId, bosId, unkId)
            tok.buildEosIds()
            return tok
        }
    }

    // ── EOS ───────────────────────────────────────────────────────────────────

    fun buildEosIds() {
        _eosTokenIds = buildSet {
            if (eosTokenId >= 0) add(eosTokenId)
            setOf(
                "<|im_end|>", "<|im_continue|>", "<|endoftext|>", "</s>", "<eos>",
                "<|eot_id|>", "<|end_of_text|>", "<end_of_turn>"
            ).forEach { s -> vocab[s]?.let { add(it) } }
        }
        Log.d(TAG, "EOS ids built: $_eosTokenIds")
    }

    fun overrideEosToken(token: String) {
        val id = vocab[token] ?: run {
            Log.w(TAG, "overrideEosToken: '$token' not in vocab")
            return
        }
        Log.d(TAG, "Override EOS: '$token' = $id")
        _eosTokenIds = _eosTokenIds + id
    }

    fun overrideBosToken(token: String) {
        // optional
    }

    fun isEos(tokenId: Long): Boolean = tokenId in _eosTokenIds

    // ── Encode ────────────────────────────────────────────────────────────────

    fun encode(text: String): List<Long> {
        val ids = mutableListOf<Long>()
        if (bosTokenId >= 0) ids.add(bosTokenId)

        // ✅ Tìm tất cả special tokens có trong vocab
        val specialTokens = vocab.keys
            .filter { it.startsWith("<|") && it.endsWith("|>") ||
                    it.startsWith("<s>") || it == "</s>" }
            .sortedByDescending { it.length }  // longer first để tránh partial match

        val byteEncoder = buildByteEncoder()
        val pattern = Regex("""'s|'t|'re|'ve|'m|'ll|'d| ?\p{L}+| ?\p{N}+| ?[^\s\p{L}\p{N}]+|\s+(?!\S)|\s+""")

        var remaining = text
        while (remaining.isNotEmpty()) {
            // ✅ Check special token trước
            val specialMatch = specialTokens.firstOrNull { remaining.startsWith(it) }
            if (specialMatch != null) {
                vocab[specialMatch]?.let { ids.add(it) }
                remaining = remaining.substring(specialMatch.length)
                continue
            }

            // Lấy đoạn text đến special token tiếp theo
            val nextSpecialIdx = specialTokens
                .mapNotNull { t -> remaining.indexOf(t).takeIf { it > 0 } }
                .minOrNull() ?: remaining.length

            val chunk = remaining.substring(0, nextSpecialIdx)
            remaining = remaining.substring(nextSpecialIdx)

            // BPE encode chunk bình thường
            val words = pattern.findAll(chunk).map { it.value }.toList()
            for (word in words) {
                val byteTokens = word.toByteArray(Charsets.UTF_8)
                    .map { byteEncoder[it.toInt() and 0xFF] ?: "?" }
                val merged = applyBPE(byteTokens)
                for (token in merged) {
                    ids.add(vocab[token] ?: unknownTokenId)
                }
            }
        }

        return ids
    }

    private fun applyBPE(tokens: List<String>): List<String> {
        if (tokens.size <= 1) return tokens
        var current = tokens.toMutableList()
        val mergeRank = mergesList.mapIndexed { idx, pair -> pair to idx }.toMap()

        while (current.size > 1) {
            var bestIdx  = -1
            var bestRank = Int.MAX_VALUE

            for (i in 0 until current.size - 1) {
                val rank = mergeRank[Pair(current[i], current[i + 1])] ?: Int.MAX_VALUE
                if (rank < bestRank) {
                    bestRank = rank
                    bestIdx  = i
                }
            }

            if (bestIdx == -1 || bestRank == Int.MAX_VALUE) break

            val merged  = current[bestIdx] + current[bestIdx + 1]
            val newList = mutableListOf<String>()
            var i = 0
            while (i < current.size) {
                if (i == bestIdx) { newList.add(merged); i += 2 }
                else              { newList.add(current[i]); i++ }
            }
            current = newList
        }
        return current
    }

    private fun buildByteEncoder(): Map<Int, String> {
        val bs = mutableListOf<Int>()
        bs.addAll('!'.code..'~'.code)
        bs.addAll('¡'.code..'¬'.code)
        bs.addAll('®'.code..'ÿ'.code)
        val cs = bs.toMutableList()
        var n = 0
        for (b in 0..255) {
            if (b !in bs) { bs.add(b); cs.add(256 + n); n++ }
        }
        return bs.zip(cs).associate { (b, c) -> b to c.toChar().toString() }
    }

    // ── Decode ────────────────────────────────────────────────────────────────

    fun decode(ids: List<Long>): String {
        val byteDecoder = buildByteDecoder()
        val bytes = mutableListOf<Byte>()

        for (id in ids) {
            if (id in _eosTokenIds) break
            val token = idToToken[id] ?: continue
            // Convert từng char trong token về byte
            for (ch in token) {
                byteDecoder[ch.toString()]?.let { bytes.add(it) }
                    ?: run {
                        // Không phải byte token — encode trực tiếp
                        bytes.addAll(ch.toString().toByteArray(Charsets.UTF_8).toList())
                    }
            }
        }

        return try {
            String(bytes.toByteArray(), Charsets.UTF_8)
        } catch (e: Exception) {
            // Fallback nếu bytes không hợp lệ
            bytes.map { it.toInt().toChar() }.joinToString("")
        }
    }

    private fun buildByteDecoder(): Map<String, Byte> {
        val encoder = buildByteEncoder()
        return encoder.entries.associate { (byte, char) -> char to byte.toByte() }
    }

    // ── Utils ─────────────────────────────────────────────────────────────────

    fun getEosId(): Long         = eosTokenId
    fun getBosId(): Long         = bosTokenId
    fun getTokenId(token: String): Long? = vocab[token]
    val vocabSize: Int get()     = vocab.size
}