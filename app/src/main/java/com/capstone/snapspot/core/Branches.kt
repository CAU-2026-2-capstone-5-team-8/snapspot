/**
 * Nearby branches of a franchise, from Kakao Local search, cached per ~1km grid cell.
 * The app passes a file-backed cache; tests and the simulator pass an in-memory one.
 */
package com.capstone.snapspot.core

import com.capstone.snapspot.core.model.Branch
import kotlinx.serialization.Serializable
import kotlin.math.floor

/** Search radius around a cell center. Covers the cell plus the refresh radius. */
const val SEARCH_RADIUS_M = 2500

/** Grid cell size in degrees (~1.1km north-south, ~0.9km east-west in Seoul). */
private const val CELL_DEG = 0.01

/** Keep results briefly: branches rarely move, and Kakao's terms discourage long-term storage. */
const val CACHE_TTL_MS = 6 * 60 * 60 * 1000L

/** Kakao returns at most 45 results (3 pages × 15) per query. */
private const val MAX_PAGES = 3
private const val PAGE_SIZE = 15

@Serializable
data class CachedBranches(val at: Long, val branches: List<Branch>)

interface BranchCache {
    suspend fun get(key: String): CachedBranches?
    suspend fun set(key: String, value: CachedBranches)
}

class MemoryBranchCache : BranchCache {
    private val m = HashMap<String, CachedBranches>()
    override suspend fun get(key: String) = m[key]
    override suspend fun set(key: String, value: CachedBranches) {
        m[key] = value
    }
}

data class Cell(val key: String, val center: Coords)

fun cellOf(p: Coords): Cell {
    val i = floor(p.latitude / CELL_DEG).toInt()
    val j = floor(p.longitude / CELL_DEG).toInt()
    return Cell("$i:$j", Coords((i + 0.5) * CELL_DEG, (j + 0.5) * CELL_DEG))
}

data class BranchResult(val branches: List<Branch>, val requests: Int)

/** Branches of `brand` near `here`. `requests` counts Kakao calls made (0 on a cache hit). */
suspend fun nearbyBranches(brand: String, here: Coords, api: KakaoApi, cache: BranchCache, now: Long): BranchResult {
    val cell = cellOf(here)
    val cacheKey = "${brandKey(brand)}|${cell.key}"
    val hit = cache.get(cacheKey)
    if (hit != null && now - hit.at < CACHE_TTL_MS) return BranchResult(hit.branches, 0)

    val docs = mutableListOf<KakaoDoc>()
    var requests = 0
    for (page in 1..MAX_PAGES) {
        val batch = api.keyword(
            mapOf(
                "query" to brand,
                "x" to cell.center.longitude.toString(),
                "y" to cell.center.latitude.toString(),
                "radius" to SEARCH_RADIUS_M.toString(),
                "sort" to "distance",
                "size" to PAGE_SIZE.toString(),
                "page" to page.toString(),
            ),
        )
        requests++
        docs += batch
        if (batch.size < PAGE_SIZE) break
    }
    val branches = pickBranches(brand, docs.distinctBy { it.id }).map {
        Branch(it.id, it.place_name, it.road_address_name.ifEmpty { it.address_name }, it.y.toDouble(), it.x.toDouble())
    }
    cache.set(cacheKey, CachedBranches(now, branches))
    return BranchResult(branches, requests)
}
