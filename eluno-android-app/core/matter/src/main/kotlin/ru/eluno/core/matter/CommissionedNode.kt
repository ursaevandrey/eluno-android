package ru.eluno.core.matter

/** Matching node comes from CHIP's comparison of both fabric ID and root public key. CASE still authenticates it. */
internal fun commissionedNode(requested: Long, errorCode: Long, matchingFabricNode: Long): Long? = when {
    errorCode == 0L -> requested
    errorCode == 126L && matchingFabricNode != 0L -> matchingFabricNode
    else -> null
}
