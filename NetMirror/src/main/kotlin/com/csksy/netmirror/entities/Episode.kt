package com.csksy.netmirror.entities

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@JsonIgnoreProperties(ignoreUnknown = true)
data class Episode(
    val complate: String? = null,
    val ep: String? = null,
    val id: String? = null,
    val s: String? = null,
    val t: String? = null,
    val time: String? = null
)
