package com.horis.cncverse.entities

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@JsonIgnoreProperties(ignoreUnknown = true)
data class EpisodesData(
    val episodes: List<Episode>?,
    val nextPage: Int,
    val nextPageSeason: String,
    val nextPageShow: Int
)
