package com.example.kyvc_androidapp.wallet.core

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.json.JSONArray
import org.xrpl.xrpl4j.codec.addresses.AddressCodec
import org.xrpl.xrpl4j.codec.addresses.UnsignedByteArray
import org.xrpl.xrpl4j.client.XrplClient
import org.xrpl.xrpl4j.model.client.accounts.AccountInfoRequestParams
import org.xrpl.xrpl4j.model.client.common.LedgerSpecifier
import org.xrpl.xrpl4j.model.transactions.Address
import org.xrpl.xrpl4j.model.transactions.CredentialCreate
import org.xrpl.xrpl4j.model.transactions.CredentialAccept
import org.xrpl.xrpl4j.model.transactions.CredentialUri
import org.xrpl.xrpl4j.model.transactions.DidData
import org.xrpl.xrpl4j.model.transactions.DidSet
import org.xrpl.xrpl4j.model.transactions.DidUri
import org.xrpl.xrpl4j.model.transactions.Payment
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
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

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

    suspend fun submitDidSet(
        seed: Seed,
        didDocumentUri: String,
        didDocumentDataHashHex: String
    ): SubmitResult<DidSet> {
        val keyPair = seed.deriveKeyPair()
        val publicKey: PublicKey = keyPair.publicKey()
        val holderAddress = publicKey.deriveAddress()
        val accountInfo = xrplClient.accountInfo(
            AccountInfoRequestParams.builder()
                .account(holderAddress)
                .ledgerSpecifier(LedgerSpecifier.VALIDATED)
                .build()
        )
        val didSet = DidSet.builder()
            .account(holderAddress)
            .uri(DidUri.of(utf8ToHex(didDocumentUri)))
            .data(DidData.of(didDocumentDataHashHex))
            .sequence(accountInfo.accountData().sequence())
            .fee(XrpCurrencyAmount.ofDrops(12))
            .signingPublicKey(publicKey)
            .build()
        val signedTx: SingleSignedTransaction<DidSet> = signatureService.sign(keyPair.privateKey(), didSet)
        return xrplClient.submit(signedTx)
    }

    suspend fun submitXrpPayment(
        seed: Seed,
        destinationAddress: String,
        amountDrops: String? = null,
        amountXrp: String? = null
    ): SubmitResult<Payment> {
        val validatedDestination = requireClassicAddress("destinationAddress", destinationAddress)
        val normalizedDrops = when {
            !amountDrops.isNullOrBlank() -> normalizeDrops(amountDrops)
            !amountXrp.isNullOrBlank() -> xrpStringToDrops(amountXrp)
            else -> throw IllegalArgumentException("amountDrops or amountXrp is required")
        }

        val keyPair = seed.deriveKeyPair()
        val publicKey: PublicKey = keyPair.publicKey()
        val sourceAddress = publicKey.deriveAddress()
        val accountInfo = xrplClient.accountInfo(
            AccountInfoRequestParams.builder()
                .account(sourceAddress)
                .ledgerSpecifier(LedgerSpecifier.VALIDATED)
                .build()
        )

        val payment = Payment.builder()
            .account(sourceAddress)
            .destination(Address.of(validatedDestination))
            .amount(XrpCurrencyAmount.ofDrops(normalizedDrops.toLong()))
            .sequence(accountInfo.accountData().sequence())
            .fee(XrpCurrencyAmount.ofDrops(12))
            .signingPublicKey(publicKey)
            .build()
        val signedTx: SingleSignedTransaction<Payment> = signatureService.sign(keyPair.privateKey(), payment)
        return xrplClient.submit(signedTx)
    }

    suspend fun getAccountTransactions(
        accountAddress: String,
        limit: Int = 10
    ): AccountTransactionsResult {
        val validatedAccount = requireClassicAddress("accountAddress", accountAddress)
        val normalizedLimit = limit.coerceIn(1, 50)
        val body = JSONObject()
            .put("method", "account_tx")
            .put(
                "params",
                JSONArray().put(
                    JSONObject()
                        .put("account", validatedAccount)
                        .put("ledger_index_min", -1)
                        .put("ledger_index_max", -1)
                        .put("limit", normalizedLimit)
                        .put("binary", false)
                        .put("forward", false)
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
                throw IllegalStateException("Invalid account_tx response")
            }
            val result = json.optJSONObject("result") ?: throw IllegalStateException("account_tx result missing")
            if (result.optString("status") == "error") {
                return AccountTransactionsResult(
                    account = validatedAccount,
                    transactions = emptyList(),
                    checkedAtUtc = Instant.now().toString(),
                    error = result.optString("error_message").ifBlank { result.optString("error").ifBlank { "account_tx failed" } }
                )
            }

            val transactions = buildList {
                val items = result.optJSONArray("transactions") ?: JSONArray()
                for (index in 0 until items.length()) {
                    val wrapper = items.optJSONObject(index) ?: continue
                    val tx = wrapper.optJSONObject("tx") ?: continue
                    val meta = wrapper.optJSONObject("meta")
                    val txType = tx.optString("TransactionType")
                    val hash = tx.optString("hash")
                    val feeDrops = tx.optString("Fee").ifBlank { null }
                    val account = tx.optString("Account")
                    val destination = tx.optString("Destination").ifBlank { null }
                    val amountAny = tx.opt("Amount")
                    val amountDrops = when (amountAny) {
                        is String -> amountAny
                        is JSONObject -> amountAny.optString("value").ifBlank { null }
                        else -> null
                    }
                    add(
                        AccountTransaction(
                            hash = hash,
                            transactionType = txType,
                            direction = when {
                                txType == "Payment" && destination == validatedAccount -> "incoming"
                                txType == "Payment" && account == validatedAccount -> "outgoing"
                                else -> "other"
                            },
                            account = account.ifBlank { validatedAccount },
                            destination = destination,
                            amountDrops = amountDrops,
                            amountXrp = amountDrops?.let(::dropsToXrpString),
                            feeDrops = feeDrops,
                            feeXrp = feeDrops?.let(::dropsToXrpString),
                            sequence = tx.optLong("Sequence").takeIf { it > 0L },
                            validated = wrapper.optBoolean("validated"),
                            ledgerIndex = wrapper.optLong("ledger_index").takeIf { it > 0L },
                            result = meta?.optString("TransactionResult")?.ifBlank { null },
                            dateUtc = tx.optLong("date").takeIf { it > 0L }?.let(::rippleEpochSecondsToUtc)
                        )
                    )
                }
            }

            AccountTransactionsResult(
                account = validatedAccount,
                transactions = transactions,
                checkedAtUtc = Instant.now().toString(),
                error = null
            )
        }
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

    suspend fun getAccountAssets(address: String): AccountAssetsResult {
        val validatedAddress = runCatching { requireClassicAddress("account", address) }.getOrElse {
            return AccountAssetsResult(
                account = address,
                accountActivated = false,
                xrpBalanceDrops = null,
                xrpBalanceXrp = null,
                ownerCount = null,
                sequence = null,
                lines = emptyList(),
                checkedAtUtc = Instant.now().toString(),
                error = it.message
            )
        }

        val accountInfoBody = JSONObject()
            .put("method", "account_info")
            .put(
                "params",
                JSONArray().put(
                    JSONObject()
                        .put("account", validatedAddress)
                        .put("ledger_index", "validated")
                )
            )
            .toString()

        val accountInfoRequest = Request.Builder()
            .url(rpcUrl)
            .post(accountInfoBody.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val checkedAt = Instant.now().toString()
        val accountInfoJson = httpClient.newCall(accountInfoRequest).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            runCatching { JSONObject(responseBody) }.getOrElse {
                throw IllegalStateException("Invalid account_info response")
            }
        }
        val accountInfoResult = accountInfoJson.optJSONObject("result")
            ?: return AccountAssetsResult(
                account = validatedAddress,
                accountActivated = false,
                xrpBalanceDrops = null,
                xrpBalanceXrp = null,
                ownerCount = null,
                sequence = null,
                lines = emptyList(),
                checkedAtUtc = checkedAt,
                error = "account_info result missing"
            )
        if (accountInfoResult.optString("status") == "error" || accountInfoResult.has("error")) {
            val errorCode = accountInfoResult.optString("error")
            val errorMessage = accountInfoResult.optString("error_message", errorCode.ifBlank { "account_info failed" })
            return AccountAssetsResult(
                account = validatedAddress,
                accountActivated = false,
                xrpBalanceDrops = null,
                xrpBalanceXrp = null,
                ownerCount = null,
                sequence = null,
                lines = emptyList(),
                checkedAtUtc = checkedAt,
                error = if (errorCode == "actNotFound") {
                    "XRPL account is not activated. Deposit XRP to this address first."
                } else {
                    errorMessage
                }
            )
        }

        val accountData = accountInfoResult.optJSONObject("account_data")
            ?: return AccountAssetsResult(
                account = validatedAddress,
                accountActivated = false,
                xrpBalanceDrops = null,
                xrpBalanceXrp = null,
                ownerCount = null,
                sequence = null,
                lines = emptyList(),
                checkedAtUtc = checkedAt,
                error = "account_data missing"
            )

        val accountLinesBody = JSONObject()
            .put("method", "account_lines")
            .put(
                "params",
                JSONArray().put(
                    JSONObject()
                        .put("account", validatedAddress)
                        .put("ledger_index", "validated")
                )
            )
            .toString()

        val accountLinesRequest = Request.Builder()
            .url(rpcUrl)
            .post(accountLinesBody.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val lines = runCatching {
            httpClient.newCall(accountLinesRequest).execute().use { response ->
                val responseBody = response.body?.string().orEmpty()
                val json = JSONObject(responseBody)
                val result = json.optJSONObject("result")
                if (result == null || result.optString("status") == "error" || result.has("error")) {
                    emptyList()
                } else {
                    val linesArray = result.optJSONArray("lines") ?: JSONArray()
                    buildList {
                        for (index in 0 until linesArray.length()) {
                            val line = linesArray.optJSONObject(index) ?: continue
                            add(
                                TrustLine(
                                    currency = line.optString("currency"),
                                    issuer = line.optString("account"),
                                    balance = line.optString("balance"),
                                    limit = line.optString("limit"),
                                    limitPeer = line.optString("limit_peer")
                                )
                            )
                        }
                    }
                }
            }
        }.getOrDefault(emptyList())

        val balanceDrops = accountData.optString("Balance").takeIf { it.isNotBlank() }
        return AccountAssetsResult(
            account = validatedAddress,
            accountActivated = true,
            xrpBalanceDrops = balanceDrops,
            xrpBalanceXrp = balanceDrops?.let(::dropsToXrpString),
            ownerCount = accountData.optLong("OwnerCount"),
            sequence = accountData.optLong("Sequence"),
            lines = lines,
            checkedAtUtc = checkedAt,
            error = null
        )
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

    private fun normalizeDrops(amountDrops: String): String {
        val normalizedDrops = amountDrops.trim()
        require(normalizedDrops.matches(Regex("^\\d+$"))) { "amountDrops must be a positive integer string" }
        require(normalizedDrops != "0") { "amountDrops must be greater than 0" }
        return normalizedDrops
    }

    private fun dropsToXrpString(drops: String): String {
        return runCatching {
            val padded = drops.trim().padStart(7, '0')
            val whole = padded.dropLast(6)
            val fractional = padded.takeLast(6).trimEnd('0')
            if (fractional.isEmpty()) whole else "$whole.$fractional"
        }.getOrDefault(drops)
    }

    private fun xrpStringToDrops(amountXrp: String): String {
        val normalized = amountXrp.trim()
        require(normalized.matches(Regex("^\\d+(\\.\\d{1,6})?$"))) {
            "amountXrp must be a positive XRP string with up to 6 decimal places"
        }
        val parts = normalized.split(".")
        val whole = parts[0]
        val fractional = parts.getOrNull(1).orEmpty().padEnd(6, '0')
        val drops = (whole + fractional).trimStart('0').ifEmpty { "0" }
        require(drops != "0") { "amountXrp must be greater than 0" }
        return drops
    }

    private fun rippleEpochSecondsToUtc(secondsSinceRippleEpoch: Long): String {
        val epochSeconds = secondsSinceRippleEpoch + RIPPLE_EPOCH_OFFSET
        return DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochSecond(epochSeconds).atOffset(ZoneOffset.UTC))
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

    data class AccountAssetsResult(
        val account: String,
        val accountActivated: Boolean,
        val xrpBalanceDrops: String?,
        val xrpBalanceXrp: String?,
        val ownerCount: Long?,
        val sequence: Long?,
        val lines: List<TrustLine>,
        val checkedAtUtc: String,
        val error: String?
    )

    data class TrustLine(
        val currency: String,
        val issuer: String,
        val balance: String,
        val limit: String,
        val limitPeer: String
    )

    data class AccountTransactionsResult(
        val account: String,
        val transactions: List<AccountTransaction>,
        val checkedAtUtc: String,
        val error: String?
    )

    data class AccountTransaction(
        val hash: String,
        val transactionType: String,
        val direction: String,
        val account: String,
        val destination: String?,
        val amountDrops: String?,
        val amountXrp: String?,
        val feeDrops: String?,
        val feeXrp: String?,
        val sequence: Long?,
        val validated: Boolean,
        val ledgerIndex: Long?,
        val result: String?,
        val dateUtc: String?
    )

    private companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val ACCEPTED_FLAG = 0x00010000L
        private const val RIPPLE_EPOCH_OFFSET = 946684800L
    }
}
