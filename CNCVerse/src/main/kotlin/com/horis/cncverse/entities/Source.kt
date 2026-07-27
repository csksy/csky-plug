package com.horis.cncverse.entities

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@JsonIgnoreProperties(ignoreUnknown = true)
data class Source(
    val file: String,
    val label: String,
    val type: String
)
