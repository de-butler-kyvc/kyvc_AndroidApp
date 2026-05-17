package com.example.kyvc_androidapp.util

object DocumentDisplayMapper {
    val documentTypeLabels: Map<String, String> = mapOf(
        "BUSINESS_REGISTRATION" to "사업자등록증",
        "BUSINESS_REGISTRATION_CERTIFICATE" to "사업자등록증",
        "KR_BUSINESS_REGISTRATION" to "사업자등록증",
        "KR_BUSINESS_REGISTRATION_CERTIFICATE" to "사업자등록증",
        "CORPORATE_REGISTRY" to "등기사항전부증명서",
        "CORPORATE_REGISTRY_CERTIFICATE" to "등기사항전부증명서",
        "CORPORATE_REGISTER" to "등기사항전부증명서",
        "KR_CORPORATE_REGISTRY" to "등기사항전부증명서",
        "KR_CORPORATE_REGISTER_FULL_CERTIFICATE" to "등기사항전부증명서",
        "SHAREHOLDER_REGISTRY" to "주주명부",
        "SHAREHOLDER_LIST" to "주주명부",
        "KR_SHAREHOLDER_REGISTRY" to "주주명부",
        "KR_SHAREHOLDER_REGISTER" to "주주명부",
        "STOCK_CHANGE_STATEMENT" to "주식변동상황명세서",
        "KR_STOCK_CHANGE_STATEMENT" to "주식변동상황명세서",
        "INVESTOR_REGISTRY" to "투자자명부",
        "KR_INVESTOR_REGISTRY" to "투자자명부",
        "MEMBER_REGISTRY" to "사원명부",
        "KR_MEMBER_REGISTRY" to "사원명부",
        "BOARD_REGISTRY" to "임원명부",
        "KR_BOARD_REGISTRY" to "임원명부",
        "ARTICLES_OF_ASSOCIATION" to "정관",
        "KR_ARTICLES_OF_ASSOCIATION" to "정관",
        "OPERATING_RULES" to "운영규정",
        "KR_OPERATING_RULES" to "운영규정",
        "REGULATIONS" to "규정",
        "KR_REGULATIONS" to "규정",
        "MEETING_MINUTES" to "회의록",
        "KR_MEETING_MINUTES" to "회의록",
        "OFFICIAL_LETTER" to "공문",
        "KR_OFFICIAL_LETTER" to "공문",
        "PURPOSE_PROOF_DOCUMENT" to "설립목적 증빙서류",
        "KR_PURPOSE_PROOF_DOCUMENT" to "설립목적 증빙서류",
        "ORGANIZATION_IDENTITY_CERTIFICATE" to "고유번호증",
        "KR_ORGANIZATION_IDENTITY_CERTIFICATE" to "고유번호증",
        "INVESTMENT_REGISTRATION_CERTIFICATE" to "외국인투자등록증명서",
        "KR_INVESTMENT_REGISTRATION_CERTIFICATE" to "외국인투자등록증명서",
        "BENEFICIAL_OWNER_PROOF_DOCUMENT" to "실소유자 증빙서류",
        "KR_BENEFICIAL_OWNER_PROOF_DOCUMENT" to "실소유자 증빙서류",
        "POWER_OF_ATTORNEY" to "위임장",
        "KR_POWER_OF_ATTORNEY" to "위임장",
        "SEAL_CERTIFICATE" to "인감증명서",
        "KR_SEAL_CERTIFICATE" to "인감증명서",
        "CORPORATE_SEAL_CERTIFICATE" to "법인인감증명서",
        "KR_CORPORATE_SEAL_CERTIFICATE" to "법인인감증명서",
        "REPRESENTATIVE_PROOF_DOCUMENT" to "대표자 확인서류",
        "KR_REPRESENTATIVE_PROOF_DOCUMENT" to "대표자 확인서류",
        "LEGAL_ENTITY_KYC_CREDENTIAL" to "법인 KYC 증명서",
        "KYC_CREDENTIAL" to "법인 KYC 증명서"
    )

    fun documentTypeLabel(code: String?, blankFallback: String = "문서"): String {
        val raw = code?.trim().orEmpty()
        if (raw.isBlank()) return blankFallback
        return documentTypeLabelOrNull(raw) ?: raw
    }

    fun documentTypeLabelOrNull(code: String?): String? {
        val raw = code?.trim().orEmpty()
        if (raw.isBlank()) return null
        return documentTypeLabels[raw] ?: documentTypeLabels[raw.uppercase()]
    }
}
