package com.horis.cncverse

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@JsonIgnoreProperties(ignoreUnknown = true)
data class StudioOption(
    val key: String,
    val label: String,
    val cookieValue: String
)
