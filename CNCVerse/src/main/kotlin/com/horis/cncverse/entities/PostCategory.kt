package com.horis.cncverse.entities

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@JsonIgnoreProperties(ignoreUnknown = true)
data class PostCategory(
    val ids: String,
    val cate: String
)
