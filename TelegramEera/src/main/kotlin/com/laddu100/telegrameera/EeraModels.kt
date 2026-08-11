package com.laddu100.telegrameera

/**
 * One search hit returned by the bridge.
 *
 *  - [title]   display name, e.g. "India's Got Latent S2 Bonus EP 2 1080p ft Badshah.mkv"
 *  - [size]    human readable size, e.g. "2.41 GB" (may be null)
 *  - [payload] opaque token used to request the file from the bot. Usually the
 *              t.me deep-link payload extracted from the clickable title, or the
 *              exact filename the bot understands.
 */
data class EeraResult(
    val title: String,
    val size: String? = null,
    val payload: String? = null,
)

/** Reply from the bridge /api/select endpoint. */
data class EeraSelectResponse(
    val fileId: String? = null,
    val fileName: String? = null,
    val size: Long? = null,
    val error: String? = null,
)

/** Reply from the bridge /api/status endpoint. */
data class EeraStatus(
    val ok: Boolean = false,
    val loggedIn: Boolean = false,
    val message: String? = null,
)
