package com.example.kyvc_androidapp.wallet.core

import cash.z.ecc.android.bip39.Mnemonics
import cash.z.ecc.android.bip39.toSeed
import com.example.kyvc_androidapp.domain.model.XrplAccount
import com.google.common.collect.Lists
import com.google.common.primitives.UnsignedInteger
import org.xrpl.xrpl4j.codec.addresses.AddressBase58
import org.xrpl.xrpl4j.codec.addresses.UnsignedByteArray
import org.xrpl.xrpl4j.crypto.keys.Base58EncodedSecret
import org.xrpl.xrpl4j.crypto.keys.Entropy
import org.xrpl.xrpl4j.crypto.keys.Seed
import java.security.SecureRandom

class WalletManager {
    fun createRandomMnemonic(): CharArray {
        val entropy = ByteArray(16).also { SecureRandom().nextBytes(it) }
        return Mnemonics.MnemonicCode(entropy).chars
    }

    fun mnemonicToSeed(mnemonic: CharArray): Seed {
        val bip39Seed = Mnemonics.MnemonicCode(mnemonic).toSeed()
        // Use first 16 bytes of BIP-39 seed as XRPL seed entropy
        val xrplEntropy = bip39Seed.copyOfRange(0, 16)
        return Seed.secp256k1SeedFromEntropy(Entropy.of(xrplEntropy))
    }

    fun mnemonicToSeed(mnemonicString: String): Seed {
        return mnemonicToSeed(mnemonicString.toCharArray())
    }

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
