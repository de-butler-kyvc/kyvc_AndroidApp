package com.example.kyvc_androidapp.wallet.core

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.xrpl.xrpl4j.client.XrplClient
import org.xrpl.xrpl4j.model.client.accounts.AccountInfoRequestParams
import org.xrpl.xrpl4j.model.client.common.LedgerSpecifier
import org.xrpl.xrpl4j.model.transactions.Address
import org.xrpl.xrpl4j.model.transactions.CredentialAccept
import org.xrpl.xrpl4j.model.transactions.XrpCurrencyAmount
import org.xrpl.xrpl4j.crypto.signing.SingleSignedTransaction
import org.xrpl.xrpl4j.crypto.keys.Seed
import org.xrpl.xrpl4j.crypto.signing.bc.BcSignatureService
import org.xrpl.xrpl4j.model.transactions.CredentialType
import org.xrpl.xrpl4j.model.client.transactions.SubmitResult
import org.xrpl.xrpl4j.crypto.keys.PublicKey

class XrplClientHelper(rpcUrl: String = "https://s.altnet.rippletest.net:51234/") {
    private val xrplClient = XrplClient(rpcUrl.toHttpUrl())
    private val signatureService = BcSignatureService()

    suspend fun submitCredentialAccept(
        seed: Seed,
        issuerAddress: String,
        credentialTypeHex: String
    ): SubmitResult<CredentialAccept> {
        val keyPair = seed.deriveKeyPair()
        val publicKey: PublicKey = keyPair.publicKey()
        val address = publicKey.deriveAddress()

        val accountInfo = xrplClient.accountInfo(
            AccountInfoRequestParams.builder()
                .account(address)
                .ledgerSpecifier(LedgerSpecifier.VALIDATED)
                .build()
        )

        val credentialAccept = CredentialAccept.builder()
            .account(address)
            .issuer(Address.of(issuerAddress))
            .credentialType(CredentialType.of(credentialTypeHex))
            .sequence(accountInfo.accountData().sequence())
            .fee(XrpCurrencyAmount.ofDrops(12))
            .signingPublicKey(publicKey)
            .build()

        val signedTx: SingleSignedTransaction<CredentialAccept> = signatureService.sign(keyPair.privateKey(), credentialAccept)
        return xrplClient.submit(signedTx)
    }
}
