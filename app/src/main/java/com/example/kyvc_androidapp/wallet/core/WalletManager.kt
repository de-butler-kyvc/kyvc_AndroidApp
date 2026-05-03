package com.example.kyvc_androidapp.wallet.core

import com.example.kyvc_androidapp.domain.model.XrplAccount
import com.google.common.collect.Lists
import com.google.common.primitives.UnsignedInteger
import org.xrpl.xrpl4j.codec.addresses.AddressBase58
import org.xrpl.xrpl4j.crypto.keys.Base58EncodedSecret
import org.xrpl.xrpl4j.crypto.keys.Seed

class WalletManager {
    fun createRandomSeed(): Seed {
        return Seed.secp256k1Seed()
    }

    fun fromSeed(seed: String): Seed {
        return Seed.fromBase58EncodedSecret(Base58EncodedSecret.of(seed))
    }

    fun seedToBase58(seed: Seed): String {
        val decodedSeed = seed.decodedSeed()
        return AddressBase58.encode(
            decodedSeed.bytes(),
            Lists.newArrayList(decodedSeed.version()),
            UnsignedInteger.valueOf(decodedSeed.bytes().length().toLong())
        )
    }

    fun getXrplAccount(seed: Seed): XrplAccount {
        val publicKey = seed.deriveKeyPair().publicKey()
        return XrplAccount(
            address = publicKey.deriveAddress().value(),
            publicKey = publicKey.base16Value()
        )
    }
}
