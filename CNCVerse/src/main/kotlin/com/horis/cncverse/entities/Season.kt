package com.horis.cncverse.entities

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@JsonIgnoreProperties(ignoreUnknown = true)
data class Season(
    val ep: String,
    val id: String,
    val s: String,
    val sele: String
)
