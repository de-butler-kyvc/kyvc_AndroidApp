package com.example.kyvc_androidapp.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.kyvc_androidapp.data.local.dao.CredentialDao
import com.example.kyvc_androidapp.data.local.dao.HolderDocumentDao
import com.example.kyvc_androidapp.data.local.entity.CredentialEntity
import com.example.kyvc_androidapp.data.local.entity.HolderDocumentEntity

@Database(
    entities = [CredentialEntity::class, HolderDocumentEntity::class],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun credentialDao(): CredentialDao
    abstract fun holderDocumentDao(): HolderDocumentDao

    companion object {
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS credentials_new (
                        credentialId TEXT NOT NULL,
                        format TEXT NOT NULL,
                        sdJwt TEXT,
                        vcJwt TEXT,
                        vcJson TEXT,
                        selectiveDisclosureJson TEXT,
                        issuerDid TEXT NOT NULL,
                        issuerAccount TEXT NOT NULL,
                        holderDid TEXT NOT NULL,
                        holderAccount TEXT NOT NULL,
                        credentialType TEXT NOT NULL,
                        vcCoreHash TEXT NOT NULL,
                        validFrom TEXT NOT NULL,
                        validUntil TEXT NOT NULL,
                        acceptedAt TEXT,
                        credentialAcceptHash TEXT,
                        revokedOrInactiveAt TEXT,
                        PRIMARY KEY(credentialId)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO credentials_new (
                        credentialId,
                        format,
                        sdJwt,
                        vcJwt,
                        vcJson,
                        selectiveDisclosureJson,
                        issuerDid,
                        issuerAccount,
                        holderDid,
                        holderAccount,
                        credentialType,
                        vcCoreHash,
                        validFrom,
                        validUntil,
                        acceptedAt,
                        credentialAcceptHash,
                        revokedOrInactiveAt
                    )
                    SELECT
                        credentialId,
                        CASE
                            WHEN instr(vcJson, '~') > 0 THEN 'dc+sd-jwt'
                            WHEN ((length(vcJson) - length(replace(vcJson, '.', ''))) = 2) THEN 'vc+jwt'
                            ELSE 'json'
                        END,
                        NULL,
                        CASE
                            WHEN instr(vcJson, '~') = 0
                                AND ((length(vcJson) - length(replace(vcJson, '.', ''))) = 2)
                            THEN vcJson
                            ELSE NULL
                        END,
                        vcJson,
                        NULL,
                        issuerDid,
                        issuerAccount,
                        holderDid,
                        holderAccount,
                        credentialType,
                        vcCoreHash,
                        validFrom,
                        validUntil,
                        acceptedAt,
                        credentialAcceptHash,
                        revokedOrInactiveAt
                    FROM credentials
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE credentials")
                db.execSQL("ALTER TABLE credentials_new RENAME TO credentials")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS holder_documents (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        documentId TEXT NOT NULL,
                        documentType TEXT NOT NULL,
                        digestSRI TEXT NOT NULL,
                        mediaType TEXT NOT NULL,
                        byteSize INTEGER NOT NULL,
                        hashInput TEXT NOT NULL,
                        encryptedBlobPath TEXT NOT NULL,
                        originalFilename TEXT NOT NULL,
                        createdAt TEXT NOT NULL,
                        importedAt TEXT NOT NULL,
                        credentialId TEXT,
                        sdJwtJti TEXT,
                        evidenceForJson TEXT NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_holder_documents_documentId ON holder_documents (documentId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_holder_documents_credentialId ON holder_documents (credentialId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_holder_documents_sdJwtJti ON holder_documents (sdJwtJti)")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_holder_documents_documentType_digestSRI " +
                        "ON holder_documents (documentType, digestSRI)"
                )
            }
        }
    }
}
