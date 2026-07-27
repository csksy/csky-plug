package com.csksy.netmirror.entities

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@JsonIgnoreProperties(ignoreUnknown = true)
data class Season(
    val ep: String? = null,
    val id: String = "",
    val s: String? = null,
    val sele: String? = null
)
