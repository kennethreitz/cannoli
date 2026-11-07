package dev.cannoli.scorza.romm.sync

import dev.cannoli.scorza.romm.cache.RommDatabase

/**
 * Resolves the RomM rom id for a local game by matching its filename against the cached RomM
 * library, the same way the browse screen decides a game is "downloaded". This is what lets save
 * sync cover pre-RomM games that were never downloaded through RomM but match a server entry.
 */
class RommCacheMatcher(private val cache: RommDatabase) {
    private data class Index(
        val byFileName: Map<String, Map<String, Int>>,
        val byTitleId: Map<String, Map<String, Int>>,
    )

    @Volatile private var index: Index? = null

    fun refresh() {
        index = build()
    }

    fun rommIdFor(tag: String, fileName: String): Int? {
        val idx = index ?: build().also { index = it }
        return idx.byFileName[tag.uppercase()]?.get(fileName.lowercase())
    }

    fun rommIdForTitleId(tag: String, titleId: String): Int? {
        val idx = index ?: build().also { index = it }
        return idx.byTitleId[tag.uppercase()]?.get(titleId.uppercase())
    }

    private fun build(): Index {
        val byFileName = HashMap<String, HashMap<String, Int>>()
        val byTitleId = HashMap<String, HashMap<String, Int>>()
        for (platform in cache.platforms()) {
            val tag = platform.cannoliTag.uppercase()
            val byName = byFileName.getOrPut(tag) { HashMap() }
            val byId = byTitleId.getOrPut(tag) { HashMap() }
            for (game in cache.allGames(platform.id)) {
                byName.putIfAbsent(game.fsName.lowercase(), game.id)
                if (tag == "PSVITA") {
                    vitaTitleId(game.fsName)?.let { byId.putIfAbsent(it, game.id) }
                }
            }
        }
        return Index(byFileName, byTitleId)
    }

    private fun vitaTitleId(fileName: String): String? {
        return VITA_TITLE_ID.find(fileName)?.value?.uppercase()
    }

    private companion object {
        val VITA_TITLE_ID = Regex("""(?i)(?<![A-Z0-9])PC[A-Z]{2}[0-9]{5}(?![A-Z0-9])""")
    }
}
