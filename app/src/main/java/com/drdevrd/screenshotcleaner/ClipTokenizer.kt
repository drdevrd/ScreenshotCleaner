package com.drdevrd.screenshotcleaner

import android.content.Context
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * CLIP BPE tokenizer implemented in Kotlin. Loads OpenAI CLIP's vocab.json
 * (48895 tokens) and merges.txt (BPE merge priorities) from assets.
 *
 * Produces a fixed 77-length IntArray with SOS + BPE tokens + EOS + zero-padding,
 * matching what the ONNX text encoder expects.
 */
class ClipTokenizer(context: Context) {

    private val encoder: Map<String, Int>
    private val bpeRanks: Map<Pair<String, String>, Int>
    private val byteEncoder: Map<Int, Char>
    private val cache = HashMap<String, List<String>>()

    val ready: Boolean

    companion object {
        const val CONTEXT_LENGTH = 77
        const val SOS = 49406
        const val EOS = 49407
        const val PAD = 0

        // CLIP uses a "byte-level" pre-tokenizer: bytes 0-255 mapped to visible unicode chars
        private fun bytesToUnicode(): Map<Int, Char> {
            val bs = mutableListOf<Int>()
            bs.addAll(('!'.code..'~'.code))
            bs.addAll(('¡'.code..'¬'.code))
            bs.addAll(('®'.code..'ÿ'.code))
            val cs = bs.toMutableList()
            var n = 0
            for (b in 0..255) {
                if (b !in bs) {
                    bs.add(b)
                    cs.add(256 + n)
                    n++
                }
            }
            return bs.zip(cs.map { it.toChar() }).toMap()
        }
    }

    init {
        var enc: Map<String, Int> = emptyMap()
        var ranks: Map<Pair<String, String>, Int> = emptyMap()
        var ok = false
        try {
            // Load vocab.json
            val vocabText = context.assets.open("clip_vocab.json").use { input ->
                InputStreamReader(input, Charsets.UTF_8).readText()
            }
            val vocabJson = JSONObject(vocabText)
            val encMap = HashMap<String, Int>(vocabJson.length())
            val keys = vocabJson.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                encMap[k] = vocabJson.getInt(k)
            }
            enc = encMap

            // Load merges.txt — each line "tok1 tok2"; rank is the line index
            val rankMap = HashMap<Pair<String, String>, Int>()
            context.assets.open("clip_merges.txt").use { input ->
                BufferedReader(InputStreamReader(input, Charsets.UTF_8)).useLines { seq ->
                    var i = 0
                    for (line in seq) {
                        if (line.isBlank() || line.startsWith("#")) continue
                        val parts = line.trim().split(" ")
                        if (parts.size == 2) {
                            rankMap[parts[0] to parts[1]] = i
                            i++
                        }
                    }
                }
            }
            ranks = rankMap
            ok = enc.isNotEmpty() && ranks.isNotEmpty()
        } catch (_: Throwable) {
            ok = false
        }
        encoder = enc
        bpeRanks = ranks
        byteEncoder = bytesToUnicode()
        ready = ok
    }

    /** Runs BPE on a single "word" (already byte-encoded). Standard CLIP algorithm. */
    private fun bpe(token: String): List<String> {
        cache[token]?.let { return it }
        if (token.isEmpty()) return emptyList()

        // Start with each char, mark last char with "</w>"
        val chars = token.toCharArray().map { it.toString() }.toMutableList()
        if (chars.isEmpty()) return emptyList()
        chars[chars.size - 1] = chars.last() + "</w>"

        while (chars.size > 1) {
            // Find pair with best (lowest) merge rank
            var bestIdx = -1
            var bestRank = Int.MAX_VALUE
            for (i in 0 until chars.size - 1) {
                val pair = chars[i] to chars[i + 1]
                val rank = bpeRanks[pair] ?: continue
                if (rank < bestRank) {
                    bestRank = rank
                    bestIdx = i
                }
            }
            if (bestIdx < 0) break
            // Merge that pair
            chars[bestIdx] = chars[bestIdx] + chars[bestIdx + 1]
            chars.removeAt(bestIdx + 1)
        }

        cache[token] = chars
        return chars
    }

    /** Encode a text string to a 77-length IntArray of token IDs. */
    fun encode(text: String): IntArray {
        val out = IntArray(CONTEXT_LENGTH) { PAD }
        out[0] = SOS
        if (!ready) return out

        val cleaned = text.lowercase().trim()
        if (cleaned.isEmpty()) {
            out[1] = EOS
            return out
        }

        // Whitespace pre-tokenize (CLIP uses a regex but whitespace works well enough)
        val words = cleaned.split(Regex("\\s+"))

        val tokens = mutableListOf<Int>()
        for (word in words) {
            if (word.isEmpty()) continue
            // Byte-level pre-encoding: each UTF-8 byte → visible unicode char
            val bytes = word.toByteArray(Charsets.UTF_8)
            val sb = StringBuilder()
            for (b in bytes) {
                val u = byteEncoder[b.toInt() and 0xFF] ?: continue
                sb.append(u)
            }
            val encoded = sb.toString()

            // Run BPE
            for (bpeToken in bpe(encoded)) {
                val id = encoder[bpeToken] ?: continue
                tokens.add(id)
                if (tokens.size >= CONTEXT_LENGTH - 2) break
            }
            if (tokens.size >= CONTEXT_LENGTH - 2) break
        }

        // [SOS] tokens... [EOS] [PAD]*
        for (i in tokens.indices) {
            out[i + 1] = tokens[i]
        }
        val eosPos = (tokens.size + 1).coerceAtMost(CONTEXT_LENGTH - 1)
        out[eosPos] = EOS
        return out
    }
}
