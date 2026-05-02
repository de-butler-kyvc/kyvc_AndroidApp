package com.example.kyvc_androidapp.wallet.core

import com.example.kyvc_androidapp.domain.model.XrplAccount
import org.xrpl.xrpl4j.wallet.DefaultWalletFactory
import org.xrpl.xrpl4j.wallet.Wallet

class WalletManager {
    private val walletFactory = DefaultWalletFactory.getInstance()

    fun createRandomWallet(isTestnet: Boolean = true): Wallet {
        return walletFactory.randomWallet(isTestnet)
    }

    fun fromSeed(seed: String, isTestnet: Boolean = true): Wallet {
        return walletFactory.fromSeed(seed, isTestnet)
    }

    fun getXrplAccount(wallet: Wallet): XrplAccount {
        return XrplAccount(
            address = wallet.classicAddress().value(),
            publicKey = wallet.publicKey()
        )
    }
}
