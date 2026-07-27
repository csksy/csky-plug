package com.horis.cncverse.entities

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

// id is mutable: providers rewrite it during suggestions post-processing
@JsonIgnoreProperties(ignoreUnknown = true)
data class Suggest(
    var id: String
)
