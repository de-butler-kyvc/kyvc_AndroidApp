package com.example.kyvc_androidapp.wallet.core

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.xrpl.xrpl4j.codec.addresses.AddressCodec
import org.xrpl.xrpl4j.codec.addresses.UnsignedByteArray
import org.xrpl.xrpl4j.client.XrplClient
import org.xrpl.xrpl4j.model.client.accounts.AccountInfoRequestParams
import org.xrpl.xrpl4j.model.client.common.LedgerSpecifier
import org.xrpl.xrpl4j.model.transactions.Address
import org.xrpl.xrpl4j.model.transactions.CredentialCreate
import org.xrpl.xrpl4j.model.transactions.CredentialAccept
import org.xrpl.xrpl4j.model.transactions.CredentialUri
import org.xrpl.xrpl4j.model.transactions.XrpCurrencyAmount
import org.xrpl.xrpl4j.crypto.signing.SingleSignedTransaction
import org.xrpl.xrpl4j.crypto.keys.Seed
import org.xrpl.xrpl4j.crypto.signing.bc.BcSignatureService
import org.xrpl.xrpl4j.model.transactions.CredentialType
import org.xrpl.xrpl4j.model.client.transactions.SubmitResult
import org.xrpl.xrpl4j.crypto.keys.PublicKey
import com.google.common.primitives.UnsignedLong
import java.security.MessageDigest
import java.time.Instant

class XrplClientHelper(rpcUrl: String = "https://s.altnet.rippletest.net:51234/") {
    private val rpcUrl = rpcUrl
    private val xrplClient = XrplClient(rpcUrl.toHttpUrl())
    private val signatureService = BcSignatureService()
    private val httpClient = OkHttpClient()
    private val addressCodec = AddressCodec.getInstance()

    suspend fun submitCredentialAccept(
        seed: Seed,
        issuerAddress: String,
        credentialTypeHex: String
    ): SubmitResult<CredentialAccept> {
        val validatedIssuer = requireClassicAddress("issuerAddress", issuerAddress)
        val keyPair = seed.deriveKeyPair()
        val publicKey: PublicKey = keyPair.publicKey()
        val address = publicKey.deriveAddress()

        ensureAccountExists(validatedIssuer, "issuerAddress")
        val accountInfo = xrplClient.accountInfo(
            AccountInfoRequestParams.builder()
                .account(address)
                .ledgerSpecifier(LedgerSpecifier.VALIDATED)
                .build()
        )

        val credentialAccept = CredentialAccept.builder()
            .account(address)
            .issuer(Address.of(validatedIssuer))
            .credentialType(CredentialType.of(credentialTypeHex))
            .sequence(accountInfo.accountData().sequence())
            .fee(XrpCurrencyAmount.ofDrops(12))
            .signingPublicKey(publicKey)
            .build()

        val signedTx: SingleSignedTransaction<CredentialAccept> = signatureService.sign(keyPair.privateKey(), credentialAccept)
        return xrplClient.submit(signedTx)
    }

    suspend fun submitCredentialCreate(
        seed: Seed,
        subjectAddress: String,
        credentialTypeHex: String,
        expirationDays: Long? = null,
        credentialUri: String? = null
    ): SubmitResult<CredentialCreate> {
        val validatedSubject = requireClassicAddress("subjectAddress", subjectAddress)
        val keyPair = seed.deriveKeyPair()
        val publicKey: PublicKey = keyPair.publicKey()
        val issuerAddress = publicKey.deriveAddress()

        ensureAccountExists(validatedSubject, "subjectAddress")
        val accountInfo = xrplClient.accountInfo(
            AccountInfoRequestParams.builder()
                .account(issuerAddress)
                .ledgerSpecifier(LedgerSpecifier.VALIDATED)
                .build()
        )

        val builder = CredentialCreate.builder()
            .account(issuerAddress)
            .subject(Address.of(validatedSubject))
            .credentialType(CredentialType.of(credentialTypeHex))
            .sequence(accountInfo.accountData().sequence())
            .fee(XrpCurrencyAmount.ofDrops(12))
            .signingPublicKey(publicKey)

        if (expirationDays != null && expirationDays > 0) {
            val expirationSeconds = Instant.now()
                .plusSeconds(expirationDays * 24L * 60L * 60L)
                .epochSecond - RIPPLE_EPOCH_OFFSET
            builder.expiration(UnsignedLong.valueOf(expirationSeconds))
        }

        credentialUri
            ?.takeIf { it.isNotBlank() }
            ?.let { builder.uri(CredentialUri.of(utf8ToHex(it))) }

        val credentialCreate = builder.build()
        val signedTx: SingleSignedTransaction<CredentialCreate> = signatureService.sign(keyPair.privateKey(), credentialCreate)
        return xrplClient.submit(signedTx)
    }

    suspend fun getCredentialStatus(
        issuerAddress: String,
        holderAddress: String,
        credentialTypeHex: String
    ): CredentialStatusResult {
        val validatedIssuer = runCatching { requireClassicAddress("issuerAddress", issuerAddress) }.getOrElse {
            return CredentialStatusResult(
                credentialIndex = "",
                found = false,
                active = false,
                accepted = false,
                issuerAccount = issuerAddress,
                holderAccount = holderAddress,
                credentialType = credentialTypeHex,
                flags = null,
                expiration = null,
                checkedAtUtc = Instant.now().toString(),
                error = it.message
            )
        }
        val validatedHolder = runCatching { requireClassicAddress("holderAddress", holderAddress) }.getOrElse {
            return CredentialStatusResult(
                credentialIndex = "",
                found = false,
                active = false,
                accepted = false,
                issuerAccount = issuerAddress,
                holderAccount = holderAddress,
                credentialType = credentialTypeHex,
                flags = null,
                expiration = null,
                checkedAtUtc = Instant.now().toString(),
                error = it.message
            )
        }
        val indexHex = credentialIndexHex(
            issuerAddress = validatedIssuer,
            holderAddress = validatedHolder,
            credentialTypeHex = credentialTypeHex
        )
        val body = JSONObject()
            .put("method", "ledger_entry")
            .put(
                "params",
                org.json.JSONArray().put(
                    JSONObject()
                        .put("index", indexHex)
                        .put("ledger_index", "validated")
                )
            )
            .toString()

        val request = Request.Builder()
            .url(rpcUrl)
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        return httpClient.newCall(request).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            val json = runCatching { JSONObject(responseBody) }.getOrElse {
                throw IllegalStateException("Invalid ledger response")
            }
            val error = json.optJSONObject("error")
                ?: json.optJSONObject("result")?.takeIf { it.optString("status") == "error" }

            if (error != null || json.optJSONObject("result")?.optString("status") == "error") {
                val errorMessage = json.optJSONObject("error")?.optString("error_message")
                    ?: json.optJSONObject("result")?.optString("error_message")
                    ?: json.optString("error")
                    ?: "ledger_entry failed"
                return CredentialStatusResult(
                    credentialIndex = indexHex,
                    found = false,
                    active = false,
                    accepted = false,
                    issuerAccount = issuerAddress,
                    holderAccount = holderAddress,
                    credentialType = credentialTypeHex,
                    flags = null,
                    expiration = null,
                    checkedAtUtc = Instant.now().toString(),
                    error = errorMessage
                )
            }

            val node = json.optJSONObject("result")?.optJSONObject("node")
                ?: json.optJSONObject("result")?.optJSONObject("ledger_entry")
                ?: throw IllegalStateException("Credential ledger node not found")
            val flags = node.optLong("Flags", 0L)
            val accepted = flags and ACCEPTED_FLAG != 0L
            val expiration = if (node.has("Expiration")) node.optLong("Expiration") else null
            val nowXrplEpochSeconds = Instant.now().epochSecond - RIPPLE_EPOCH_OFFSET
            val active = accepted && (expiration == null || expiration > nowXrplEpochSeconds)

            CredentialStatusResult(
                credentialIndex = indexHex,
                found = true,
                active = active,
                accepted = accepted,
                issuerAccount = node.optString("Issuer", issuerAddress),
                holderAccount = node.optString("Subject", holderAddress),
                credentialType = node.optString("CredentialType", credentialTypeHex),
                flags = flags,
                expiration = expiration,
                checkedAtUtc = Instant.now().toString(),
                error = null
            )
        }
    }

    fun credentialIndexHex(
        issuerAddress: String,
        holderAddress: String,
        credentialTypeHex: String
    ): String {
        val issuerBytes = addressCodec.decodeAccountId(Address.of(requireClassicAddress("issuerAddress", issuerAddress))).toByteArray()
        val holderBytes = addressCodec.decodeAccountId(Address.of(requireClassicAddress("holderAddress", holderAddress))).toByteArray()
        val credentialTypeBytes = UnsignedByteArray.fromHex(credentialTypeHex).toByteArray()
        val input = ByteArray(2 + holderBytes.size + issuerBytes.size + credentialTypeBytes.size)
        input[0] = 0x00
        input[1] = 0x44
        holderBytes.copyInto(input, destinationOffset = 2)
        issuerBytes.copyInto(input, destinationOffset = 2 + holderBytes.size)
        credentialTypeBytes.copyInto(input, destinationOffset = 2 + holderBytes.size + issuerBytes.size)

        val digest = MessageDigest.getInstance("SHA-512").digest(input)
        return digest.copyOfRange(0, 32).joinToString("") { "%02X".format(it) }
    }

    private fun requireClassicAddress(label: String, value: String): String {
        val address = value.trim()
        require(address.isNotBlank()) { "$label is required" }
        runCatching { Address.of(address) }.getOrElse {
            val hint = if (
                address.contains("testnet", ignoreCase = true) ||
                address.contains("example", ignoreCase = true) ||
                address.contains("placeholder", ignoreCase = true) ||
                address.contains("accountfortestnet", ignoreCase = true)
            ) {
                "This looks like sample data. Replace it with a real XRPL testnet classic address."
            } else {
                "Use the XRPL classic address itself, not a DID."
            }
            throw IllegalArgumentException("$label must be a real XRPL classic address (25-35 chars, starts with r): $address. $hint")
        }
        return address
    }

    private fun utf8ToHex(value: String): String {
        return value.toByteArray(Charsets.UTF_8).joinToString(separator = "") { "%02X".format(it) }
    }

    private suspend fun ensureAccountExists(address: String, label: String) {
        runCatching {
            xrplClient.accountInfo(
                AccountInfoRequestParams.builder()
                    .account(Address.of(address))
                    .ledgerSpecifier(LedgerSpecifier.VALIDATED)
                    .build()
            )
        }.getOrElse { error ->
            throw IllegalArgumentException(
                "$label must exist on XRPL testnet and be funded before submitting this transaction: ${error.message}",
                error
            )
        }
    }

    data class CredentialStatusResult(
        val credentialIndex: String,
        val found: Boolean,
        val active: Boolean,
        val accepted: Boolean,
        val issuerAccount: String,
        val holderAccount: String,
        val credentialType: String,
        val flags: Long?,
        val expiration: Long?,
        val checkedAtUtc: String,
        val error: String?
    )

    private companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val ACCEPTED_FLAG = 0x00010000L
        private const val RIPPLE_EPOCH_OFFSET = 946684800L
    }
}
