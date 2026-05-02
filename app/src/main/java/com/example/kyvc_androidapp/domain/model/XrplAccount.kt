package com.example.kyvc_androidapp.domain.model

data class XrplAccount(
    val address: String,
    val publicKey: String,
    val did: String = "did:xrpl:1:$address"
)
