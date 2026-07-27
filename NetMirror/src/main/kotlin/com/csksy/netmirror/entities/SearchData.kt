package com.csksy.netmirror.entities

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@JsonIgnoreProperties(ignoreUnknown = true)
data class SearchData(
    val head: String? = null,
    val searchResult: List<SearchResult> = emptyList(),
    val type: Int = 0
)
