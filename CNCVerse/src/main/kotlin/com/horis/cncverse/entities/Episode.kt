package com.horis.cncverse.entities

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@JsonIgnoreProperties(ignoreUnknown = true)
data class Episode(
    val complate: String,
    val ep: String,
    val id: String,
    val s: String,
    val t: String,
    val time: String
)
