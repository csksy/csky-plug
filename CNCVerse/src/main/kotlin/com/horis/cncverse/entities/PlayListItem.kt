package com.horis.cncverse.entities

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@JsonIgnoreProperties(ignoreUnknown = true)
data class PlayListItem(
    val image: String?,
    val image2: String?,
    val sources: List<Source>,
    val tracks: List<Tracks>?,
    val title: String
)
