package com.csksy.netmirror.entities

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@JsonIgnoreProperties(ignoreUnknown = true)
data class EpisodesData(
    val episodes: List<Episode>? = null,
    val nextPageShow: Int = 0,
    val nextPageSeason: String? = null,
    val season: Int = 0
)
