package com.csksy.netmirror.entities

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@JsonIgnoreProperties(ignoreUnknown = true)
data class PostData(
    val title: String = "",
    val desc: String? = null,
    val year: String? = null,
    val runtime: String? = null,
    val genre: String? = null,
    val cast: String? = null,
    val match: String? = null,
    val ua: String? = null,
    val episodes: List<Episode> = emptyList(),
    val season: List<Season>? = null,
    val suggest: List<Suggest>? = null,
    val nextPageShow: Int = 0,
    val nextPageSeason: String? = null
)
