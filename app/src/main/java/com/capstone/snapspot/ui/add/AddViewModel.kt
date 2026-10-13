package com.capstone.snapspot.ui.add

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.capstone.snapspot.BuildConfig
import com.capstone.snapspot.SnapSpotApp
import com.capstone.snapspot.core.Candidate
import com.capstone.snapspot.core.Coords
import com.capstone.snapspot.core.distanceMeters
import com.capstone.snapspot.core.findDuplicateCoupon
import com.capstone.snapspot.core.findDuplicatePlace
import com.capstone.snapspot.core.model.Branch
import com.capstone.snapspot.core.model.Coupon
import com.capstone.snapspot.core.model.Place
import com.capstone.snapspot.core.nearbyBranches
import com.capstone.snapspot.image.Screenshots
import com.capstone.snapspot.location.geocodePlace
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

sealed interface SaveResult {
    data class Saved(val kind: String, val id: String) : SaveResult
    data class DuplicatePlace(val existing: Place) : SaveResult
    data class DuplicateCoupon(val existing: Coupon) : SaveResult
    data object NotReady : SaveResult
}

/** One screenshot on its way to becoming a saved place or coupon. */
class AddViewModel(app: Application) : AndroidViewModel(app) {
    private val c = (app as SnapSpotApp).container
    private val _draft = MutableStateFlow<Draft?>(null)
    val draft: StateFlow<Draft?> = _draft.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    /** Shown once as a dialog, then cleared. */
    val error: StateFlow<String?> = _error.asStateFlow()

    val here get() = c.location.here

    private val kakaoOrNull get() = c.kakao.takeIf { BuildConfig.KAKAO_REST_KEY.isNotEmpty() }

    fun hasGeminiKey() = BuildConfig.GEMINI_API_KEY.isNotEmpty()

    fun clearError() {
        _error.value = null
    }

    fun update(f: (Draft) -> Draft) = _draft.update { it?.let(f) }

    fun cancel() {
        _draft.value = null
    }

    private suspend fun doLocate(name: String, address: String, category: String) =
        locate(name, address, category, kakaoOrNull) { n, a -> geocodePlace(getApplication(), n, a) }

    /** Branches of `brand` around the user, nearest first; null when the position is unknown. */
    private suspend fun previewBranches(brand: String): List<Branch>? {
        val at = c.location.current() ?: return null
        val api = kakaoOrNull ?: return emptyList()
        if (brand.isBlank()) return null
        return try {
            nearbyBranches(brand, at, api, c.branchCache, System.currentTimeMillis()).branches
                .sortedBy { distanceMeters(at, Coords(it.latitude, it.longitude)) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    fun start(uri: Uri) {
        val base = Draft(stage = Stage.READING, kind = "place", imageUri = uri.toString())
        _draft.value = base
        viewModelScope.launch {
            try {
                val shot = Screenshots.prepare(getApplication(), uri)
                val ex = c.gemini.extract(shot.base64Jpeg)
                val read = base.copy(
                    stage = Stage.LOCATING, imageUri = shot.fileUri, name = ex.name, address = ex.address,
                    category = ex.category, note = ex.note, endDate = ex.endDate.orEmpty(), isPlace = ex.isPlace,
                    kind = ex.kind, brand = ex.brand, benefit = ex.benefit,
                )
                _draft.value = read
                if (ex.kind == "coupon") {
                    val branches = previewBranches(ex.brand)
                    _draft.update { d -> d?.takeIf { it.imageUri == read.imageUri }?.copy(branches = branches, stage = Stage.READY) ?: d }
                    return@launch
                }
                val located = if (ex.isPlace) doLocate(ex.name, ex.address, ex.category) else Located(null, null, emptyList())
                _draft.update { d ->
                    d?.takeIf { it.imageUri == read.imageUri }
                        ?.copy(coords = located.coords, matched = located.matched, candidates = located.candidates, stage = Stage.READY) ?: d
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _draft.update { it?.copy(stage = Stage.READY) }
                _error.value = "스크린샷을 읽지 못했어요\n${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    /** Switching a draft between place and coupon (Gemini can guess wrong). */
    fun setKind(kind: String) {
        val d = _draft.value ?: return
        if (d.kind == kind) return
        viewModelScope.launch {
            if (kind == "coupon") {
                val brand = d.brand.ifBlank { d.name }
                update { it.copy(kind = kind, brand = brand, branches = null) }
                val branches = previewBranches(brand)
                update { it.copy(branches = branches) }
            } else {
                update { it.copy(kind = kind) }
                if (d.coords == null) {
                    val l = doLocate(d.name, d.address, d.category)
                    update { it.copy(coords = l.coords, matched = l.matched, candidates = l.candidates) }
                }
            }
        }
    }

    fun regeocode() {
        val d = _draft.value ?: return
        viewModelScope.launch {
            _busy.value = true
            val l = doLocate(d.name, d.address, d.category)
            update { it.copy(coords = l.coords, matched = l.matched, candidates = l.candidates) }
            _busy.value = false
            if (l.coords == null) _error.value = "위치를 못 찾았어요\n주소를 더 구체적으로 고치거나 \"지금 위치로\"를 눌러 주세요."
        }
    }

    fun refreshBranchPreview() {
        val d = _draft.value ?: return
        viewModelScope.launch {
            _busy.value = true
            val branches = previewBranches(d.brand)
            update { it.copy(branches = branches) }
            _busy.value = false
        }
    }

    fun pickCandidate(cand: Candidate) = update {
        it.copy(
            name = if (cand.source == "keyword") cand.name else it.name,
            address = cand.address,
            coords = Coords(cand.latitude, cand.longitude),
            matched = cand,
        )
    }

    fun pinToHere() {
        viewModelScope.launch {
            val at = c.location.current() ?: return@launch
            update { it.copy(coords = at, matched = null) }
        }
    }

    /** Saves the draft. A duplicate is returned instead of saved unless `allowDuplicate` (coupons only). */
    suspend fun save(allowDuplicate: Boolean = false): SaveResult {
        val d = _draft.value ?: return SaveResult.NotReady
        val now = System.currentTimeMillis()
        val result = if (d.kind == "coupon") {
            val coupon = buildCoupon(d, "c_${now.toString(36)}", now) ?: return SaveResult.NotReady
            if (!allowDuplicate) {
                findDuplicateCoupon(c.repo.coupons.value, coupon.brand, coupon.benefit, coupon.expiresAt)
                    ?.let { return SaveResult.DuplicateCoupon(it) }
            }
            c.repo.upsertCoupon(coupon)
            SaveResult.Saved("coupon", coupon.id)
        } else {
            val place = buildPlace(d, "p_${now.toString(36)}", now) ?: return SaveResult.NotReady
            findDuplicatePlace(c.repo.places.value, place.name, place.kakaoId, Coords(place.latitude, place.longitude))
                ?.let { return SaveResult.DuplicatePlace(it) }
            c.repo.upsertPlace(place)
            SaveResult.Saved("place", place.id)
        }
        _draft.value = null
        c.geofenceSync.sync(c.location.here.value)
        return result
    }
}
