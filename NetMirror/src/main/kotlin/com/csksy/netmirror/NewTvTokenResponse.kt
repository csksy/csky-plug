package com.csksy.netmirror

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@JsonIgnoreProperties(ignoreUnknown = true)
data class NewTvTokenResponse(
    val token_hash: String? = null
)
