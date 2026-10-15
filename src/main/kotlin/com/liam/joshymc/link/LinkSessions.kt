package com.liam.joshymc.link

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Tunables for linking, read from `discord.linking` in config.yml. */
data class LinkSettings(
    val codeLength: Int = 8,
    val codeExpiryMs: Long = 300_000,
    val maxFailedAttempts: Int = 5,
    val attemptCooldownMs: Long = 600_000,
    val globalMaxFailuresPerMinute: Int = 120,
)

/** The Discord account that DMed a pending link's code. */
data class DiscordClaim(val id: String, val displayName: String, val username: String)

/**
 * One player's `/link` session. Immutable: claiming swaps in a copy, so a reader on any
 * thread sees either the unclaimed or the claimed session, never half of one.
 */
data class PendingLink(
    val playerUuid: UUID,
    val playerName: String,
    val code: String,
    val createdAt: Long,
    val expiresAt: Long,
    val claim: DiscordClaim? = null,
) {
    fun isExpired(now: Long): Boolean = now >= expiresAt
}

/**
 * Pending link sessions plus the persistent Minecraft <-> Discord links (issue #1155).
 *
 * Every state change happens under one private lock that only guards these in-memory maps
 * (no I/O, no Bukkit calls), so the JDA thread submitting a code and the main thread
 * confirming or cancelling can't interleave. Links stay strictly one-to-one: linking
 * replaces both the player's old Discord account and the Discord account's old player.
 *
 * A claim is written once: when the GUI shows a Discord account, Confirm can only link that
 * account. Knowing the code is never enough on its own; the player still has to confirm.
 */
class LinkSessions(
    @Volatile var settings: LinkSettings = LinkSettings(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val codeSource: (Int) -> String = LinkCodes::generate,
) {

    sealed interface SubmitResult {
        data class Accepted(val pending: PendingLink) : SubmitResult
        /** Wrong, expired or already-claimed code; deliberately indistinguishable. */
        data object Invalid : SubmitResult
        data object NotACode : SubmitResult
        data class LockedOut(val retryAfterMs: Long) : SubmitResult
        data class Busy(val retryAfterMs: Long) : SubmitResult
    }

    sealed interface ConfirmResult {
        data class Linked(val pending: PendingLink, val claim: DiscordClaim, val replacedPlayer: UUID?) : ConfirmResult
        /** Still waiting for a Discord DM; nothing to confirm yet. */
        data object NotClaimed : ConfirmResult
        data object Expired : ConfirmResult
    }

    private val lock = Any()
    private val limiter = LinkAttemptLimiter()
    private val pendingByCode = HashMap<String, PendingLink>()
    private val pendingByPlayer = HashMap<UUID, PendingLink>()

    // Written only under [lock]; concurrent maps so lookups stay lock-free.
    private val links = ConcurrentHashMap<UUID, String>()
    private val reverseLinks = ConcurrentHashMap<String, UUID>()

    /** Starts a fresh session for the player, replacing only that player's old one. */
    fun start(playerUuid: UUID, playerName: String): PendingLink = synchronized(lock) {
        val now = clock()
        pruneExpired(now)
        removePending(playerUuid)
        val length = settings.codeLength.coerceIn(LinkCodes.MIN_LENGTH, LinkCodes.MAX_LENGTH)
        var code = codeSource(length)
        var tries = 1
        while (code in pendingByCode) {
            check(tries++ < MAX_CODE_TRIES) { "Could not generate a unique link code" }
            code = codeSource(length)
        }
        val pending = PendingLink(playerUuid, playerName, code, now, now + settings.codeExpiryMs)
        pendingByCode[code] = pending
        pendingByPlayer[playerUuid] = pending
        pending
    }

    /** A code DMed to the bot by [claim]'s account. */
    fun submit(claim: DiscordClaim, input: String): SubmitResult = synchronized(lock) {
        val now = clock()
        val settings = settings
        when (val decision = limiter.check(claim.id, now, settings)) {
            is LinkAttemptLimiter.Decision.LockedOut -> return SubmitResult.LockedOut(decision.retryAfterMs)
            is LinkAttemptLimiter.Decision.Busy -> return SubmitResult.Busy(decision.retryAfterMs)
            LinkAttemptLimiter.Decision.Allowed -> {}
        }
        val code = LinkCodes.normalize(input, settings.codeLength.coerceIn(LinkCodes.MIN_LENGTH, LinkCodes.MAX_LENGTH))
            ?: return SubmitResult.NotACode

        pruneExpired(now)
        val pending = pendingByCode[code]
        if (pending == null || (pending.claim != null && pending.claim.id != claim.id)) {
            limiter.recordFailure(claim.id, now, settings)
            return SubmitResult.Invalid
        }

        limiter.recordSuccess(claim.id)
        val claimed = if (pending.claim != null) pending else pending.copy(claim = claim)
        pendingByCode[code] = claimed
        pendingByPlayer[claimed.playerUuid] = claimed
        SubmitResult.Accepted(claimed)
    }

    /** The player's Confirm click. Links whatever account the GUI showed, or nothing. */
    fun confirm(playerUuid: UUID): ConfirmResult = synchronized(lock) {
        val now = clock()
        val pending = pendingByPlayer[playerUuid] ?: return ConfirmResult.Expired
        if (pending.isExpired(now)) {
            removePending(playerUuid)
            return ConfirmResult.Expired
        }
        val claim = pending.claim ?: return ConfirmResult.NotClaimed
        removePending(playerUuid)
        // The account is now spoken for: other sessions it claimed can't be confirmed too.
        pendingByPlayer.values.filter { it.claim?.id == claim.id }.forEach { removePending(it.playerUuid) }

        val replaced = putLink(playerUuid, claim.id)
        ConfirmResult.Linked(pending, claim, replaced)
    }

    fun cancel(playerUuid: UUID) = synchronized(lock) { removePending(playerUuid) }

    /** The player's live session, or null once it was confirmed, cancelled or expired. */
    fun current(playerUuid: UUID): PendingLink? = synchronized(lock) {
        pendingByPlayer[playerUuid]?.takeUnless { it.isExpired(clock()) }
    }

    /** Removes the player's link in both directions; returns the Discord id it had. */
    fun unlink(playerUuid: UUID): String? = synchronized(lock) {
        val discordId = links.remove(playerUuid) ?: return null
        reverseLinks.remove(discordId, playerUuid)
        discordId
    }

    fun getDiscordId(playerUuid: UUID): String? = links[playerUuid]
    fun getMinecraftUuid(discordId: String): UUID? = reverseLinks[discordId]
    fun isLinked(playerUuid: UUID): Boolean = links.containsKey(playerUuid)
    fun linkCount(): Int = links.size

    fun snapshotLinks(): Map<UUID, String> = synchronized(lock) { HashMap(links) }

    /**
     * Replaces every stored link (load / reload). If the file maps one Discord account to
     * several players (left behind by the old relink bug), the first entry is kept and the
     * rest are returned so the caller can log them; the next save drops them.
     */
    fun replaceLinks(entries: List<Pair<UUID, String>>): List<Pair<UUID, String>> = synchronized(lock) {
        links.clear()
        reverseLinks.clear()
        val dropped = ArrayList<Pair<UUID, String>>()
        for ((uuid, discordId) in entries) {
            if (links.containsKey(uuid) || reverseLinks.containsKey(discordId)) {
                dropped += uuid to discordId
                continue
            }
            links[uuid] = discordId
            reverseLinks[discordId] = uuid
        }
        dropped
    }

    /** Links the pair, clearing both sides' old links. Returns a player who lost the account. */
    private fun putLink(playerUuid: UUID, discordId: String): UUID? {
        links.remove(playerUuid)?.let { reverseLinks.remove(it, playerUuid) }
        val previous = reverseLinks.remove(discordId)?.takeIf { it != playerUuid }
        previous?.let { links.remove(it, discordId) }
        links[playerUuid] = discordId
        reverseLinks[discordId] = playerUuid
        return previous
    }

    private fun removePending(playerUuid: UUID) {
        val old = pendingByPlayer.remove(playerUuid) ?: return
        if (pendingByCode[old.code]?.playerUuid == playerUuid) pendingByCode.remove(old.code)
    }

    private fun pruneExpired(now: Long) {
        pendingByPlayer.values.filter { it.isExpired(now) }.forEach { removePending(it.playerUuid) }
    }

    companion object {
        private const val MAX_CODE_TRIES = 100
    }
}
