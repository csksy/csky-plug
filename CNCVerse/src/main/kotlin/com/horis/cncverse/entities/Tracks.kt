package com.horis.cncverse.entities

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@JsonIgnoreProperties(ignoreUnknown = true)
data class Tracks(
    val kind: String?,
    val file: String?,
    val label: String?
)
