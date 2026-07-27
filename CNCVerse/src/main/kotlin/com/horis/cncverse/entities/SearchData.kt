package com.horis.cncverse.entities

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@JsonIgnoreProperties(ignoreUnknown = true)
data class SearchData(
    val head: String,
    val searchResult: List<SearchResult>,
    val type: Int
)
