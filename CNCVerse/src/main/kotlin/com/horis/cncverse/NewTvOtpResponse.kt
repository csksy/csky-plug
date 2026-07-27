package com.horis.cncverse

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@JsonIgnoreProperties(ignoreUnknown = true)
data class NewTvOtpResponse(
    val otp: String? = null,
    val status: String? = null,
    val usertoken: String? = null,
    val pub_msg: String? = null,
    val pub_msg_f_size: Int? = null,
    val pub_msg_color: String? = null,
    val error_msg: String? = null
)
