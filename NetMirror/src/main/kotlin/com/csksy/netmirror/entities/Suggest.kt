package com.csksy.netmirror.entities

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@JsonIgnoreProperties(ignoreUnknown = true)
data class Suggest(
    var id: String = "",
    val t: String? = null
)
