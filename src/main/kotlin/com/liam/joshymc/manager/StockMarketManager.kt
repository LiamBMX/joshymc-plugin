package com.liam.joshymc.manager

import com.liam.joshymc.Joshymc
import com.liam.joshymc.util.ProfanityFilter
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import java.sql.ResultSet
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Owns the player-driven stock market: table creation, stock creation flow,
 * buy/sell orchestration, holdings/portfolio queries, ticker generation, name
 * validation, and the one-time migration of the old `bank_investments` table.
 *
 * Two pricing models live side by side, selected per-stock via `is_server_owned`:
 * - Player-created stocks use the [StockPricingEngine] bonding curve (tanh-bounded
 *   trade impact against market-cap liquidity).
 * - Server-owned stocks (currently just JOSH) use the [ServerStockPricingEngine]
 *   nonlinear supply curve — price is a direct function of circulating supply, with
 *   no server-funded contribution/adjustment on either side of a trade.
 *
 * Pricing/persistence assumption: this is a bonding-curve market, not an order book —
 * buys mint shares_outstanding, sells burn it, and there is no counterparty. Trades
 * always execute at the average of pre/post price, which is what makes an immediate
 * buy-then-sell of the same stock a net loss (prevents free-money round-tripping).
 */
class StockMarketManager(private val plugin: Joshymc) {

    companion object {
        /** Fixed UUID string used to store JoshyMC's own server-controlled position as a normal holdings row. */
        const val SERVER_STOCK_UUID_STRING = "00000000-0000-0000-0000-000000000001"
        val SERVER_STOCK_UUID: UUID = UUID.fromString(SERVER_STOCK_UUID_STRING)
        const val JOSH_TICKER = "JOSH"
        const val JOSH_NAME = "JoshyMC"

        /** Below this, share counts / market caps are treated as effectively zero. */
        const val EPSILON = StockPricingEngine.EPSILON
    }

    // ── Config (loaded in start()) ──────────────────────────────────
    var minimumBuy = 1000.0
        private set
    var stockCreationCost = 1_000_000.0
        private set
    var defaultStockPrice = 10.0
        private set
    var initialShares = 100_000.0
        private set
    var maxSingleTradeImpact = 0.10
        private set
    var minLiquidityFloor = 1000.0
        private set
    var serverStockCurveStrength = 4.0
        private set
    var serverStockCurveExponent = 1.6
        private set
    var serverStockMinimumPrice = 10.0
        private set
    var serverStockMaximumPrice = 250_000.0
        private set
    var activityPeriodHours = 24
        private set
    var largeTransactionThreshold = 500_000.0
        private set
    var chatInputTimeoutSeconds = 30
        private set
    var nameMinLength = 3
        private set
    var nameMaxLength = 24
        private set

    private var activityVeryHigh = 1_000_000.0
    private var activityHigh = 250_000.0
    private var activityModerate = 50_000.0
    private var activityLow = 5_000.0
    private var trendStableBandPercent = 2.0

    var iconMaterials: List<Material> = emptyList()
        private set

    /** Tickers/names (lowercased) that `/invest admin reset|delete` may never target. */
    var protectedStocks: Set<String> = emptySet()
        private set

    private val chatInputTimeoutMs: Long get() = chatInputTimeoutSeconds * 1000L

    // ── Pending chat-input state (consumed by StockTradeChatListener) ──
    data class PendingCreateName(val expiresAt: Long)
    data class PendingTradeInput(val ticker: String, val expiresAt: Long)
    data class PendingStockCreation(val name: String, val ticker: String, val expiresAt: Long)
    data class PendingTradeConfirmation(
        val ticker: String,
        val dollarAmount: Double,
        val isBuy: Boolean,
        val expiresAt: Long,
    )

    val pendingCreateNameInputs = ConcurrentHashMap<UUID, PendingCreateName>()
    val pendingBuyInputs = ConcurrentHashMap<UUID, PendingTradeInput>()
    val pendingSellInputs = ConcurrentHashMap<UUID, PendingTradeInput>()
    val pendingStockConfirmations = ConcurrentHashMap<UUID, PendingStockCreation>()
    val pendingTradeConfirmations = ConcurrentHashMap<UUID, PendingTradeConfirmation>()

    // ── Admin reset/delete pending confirmations ────────────────────
    // Keyed by "admin key" (player UUID string, or "CONSOLE") rather than UUID, since
    // console is allowed to run these — only the same admin who requested an action can
    // confirm/cancel it.
    enum class AdminActionType(val verb: String, val pastTense: String) {
        RESET("reset", "reset"),
        DELETE("delete", "deleted"),
    }

    data class PendingAdminAction(
        val actionType: AdminActionType,
        val ticker: String,
        val expiresAt: Long,
    )

    sealed class AdminActionPreview {
        data class Ready(val stock: Stock, val investorCount: Int, val refundTotal: Double) : AdminActionPreview()
        data class Failure(val message: String) : AdminActionPreview()
    }

    sealed class AdminActionResult {
        data class Success(
            val actionType: AdminActionType,
            val ticker: String,
            val investorsRefunded: Int,
            val totalRefunded: Double,
        ) : AdminActionResult()
        data class Failure(val message: String) : AdminActionResult()
    }

    val pendingAdminActions = ConcurrentHashMap<String, PendingAdminAction>()

    /** Only one pending chat-input type may be active per player at a time. */
    fun setPendingCreateName(uuid: UUID) {
        pendingBuyInputs.remove(uuid)
        pendingSellInputs.remove(uuid)
        pendingCreateNameInputs[uuid] = PendingCreateName(System.currentTimeMillis() + chatInputTimeoutMs)
    }

    fun setPendingBuy(uuid: UUID, ticker: String) {
        pendingCreateNameInputs.remove(uuid)
        pendingSellInputs.remove(uuid)
        pendingBuyInputs[uuid] = PendingTradeInput(ticker, System.currentTimeMillis() + chatInputTimeoutMs)
    }

    fun setPendingSell(uuid: UUID, ticker: String) {
        pendingCreateNameInputs.remove(uuid)
        pendingBuyInputs.remove(uuid)
        pendingSellInputs[uuid] = PendingTradeInput(ticker, System.currentTimeMillis() + chatInputTimeoutMs)
    }

    fun clearAllPending(uuid: UUID) {
        pendingCreateNameInputs.remove(uuid)
        pendingBuyInputs.remove(uuid)
        pendingSellInputs.remove(uuid)
        pendingStockConfirmations.remove(uuid)
        pendingTradeConfirmations.remove(uuid)
    }

    fun isExpired(expiresAt: Long): Boolean = System.currentTimeMillis() > expiresAt

    fun needsConfirmation(dollarAmount: Double): Boolean = dollarAmount >= largeTransactionThreshold

    // ── Data classes ─────────────────────────────────────────────────
    data class Stock(
        val ticker: String,
        val name: String,
        val nameLower: String,
        val creatorUuid: String?,
        val icon: Material,
        val price: Double,
        val sharesOutstanding: Double,
        val isServerOwned: Boolean,
        val createdAt: Long,
    )

    data class Holding(
        val ticker: String,
        val uuid: String,
        val shares: Double,
        val costBasis: Double,
    )

    data class MarketStats(
        val volume24h: Double,
        val buyVolume24h: Double,
        val sellVolume24h: Double,
        val open24h: Double,
        val high24h: Double,
        val low24h: Double,
        val changePercent24h: Double,
        val activity: StockPricingEngine.ActivityLevel,
        val trend: StockPricingEngine.TrendLevel,
    )

    sealed class TradeOutcome {
        data class BuySuccess(
            val stock: Stock,
            val dollarAmount: Double,
            val sharesMinted: Double,
            val avgExecutionPrice: Double,
            val previousPrice: Double,
            val newPrice: Double,
        ) : TradeOutcome()

        data class SellSuccess(
            val stock: Stock,
            val dollarAmount: Double,
            val sharesSold: Double,
            val avgExecutionPrice: Double,
            val previousPrice: Double,
            val newPrice: Double,
            val realizedPL: Double,
            val remainingShares: Double,
            val remainingValue: Double,
        ) : TradeOutcome()

        data class Failure(val message: String) : TradeOutcome()
    }

    sealed class CreateOutcome {
        data class Success(val stock: Stock) : CreateOutcome()
        data class Failure(val message: String) : CreateOutcome()
    }

    // ── Lifecycle ────────────────────────────────────────────────────

    fun start() {
        plugin.databaseManager.createTable("""
            CREATE TABLE IF NOT EXISTS stocks (
                ticker TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                name_lower TEXT NOT NULL UNIQUE,
                creator_uuid TEXT,
                icon TEXT NOT NULL,
                price REAL NOT NULL,
                shares_outstanding REAL NOT NULL,
                is_server_owned INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL
            )
        """.trimIndent())

        plugin.databaseManager.createTable("""
            CREATE TABLE IF NOT EXISTS stock_holdings (
                ticker TEXT NOT NULL,
                uuid TEXT NOT NULL,
                shares REAL NOT NULL DEFAULT 0,
                cost_basis REAL NOT NULL DEFAULT 0,
                PRIMARY KEY (ticker, uuid)
            )
        """.trimIndent())

        plugin.databaseManager.createTable("""
            CREATE TABLE IF NOT EXISTS stock_trades (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                ticker TEXT NOT NULL,
                uuid TEXT NOT NULL,
                is_buy INTEGER NOT NULL,
                dollar_amount REAL NOT NULL,
                shares REAL NOT NULL,
                price REAL NOT NULL,
                server_triggered INTEGER NOT NULL DEFAULT 0,
                timestamp INTEGER NOT NULL
            )
        """.trimIndent())

        plugin.databaseManager.createTable("""
            CREATE TABLE IF NOT EXISTS stock_admin_actions (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                ticker TEXT NOT NULL,
                name TEXT NOT NULL,
                action TEXT NOT NULL,
                admin_name TEXT NOT NULL,
                investors_refunded INTEGER NOT NULL,
                total_refunded REAL NOT NULL,
                timestamp INTEGER NOT NULL
            )
        """.trimIndent())

        loadConfig()
        migrateOldBankInvestments()
        ensureJoshyMcStock()

        plugin.logger.info("[StockMarket] StockMarketManager started (${getAllStocks().size} stocks).")
    }

    private fun loadConfig() {
        val cfg = plugin.config
        minimumBuy = cfg.getDouble("stock-market.minimum-buy", 1000.0)
        stockCreationCost = cfg.getDouble("stock-market.stock-creation-cost", 1_000_000.0)
        defaultStockPrice = cfg.getDouble("stock-market.default-stock-price", 10.0)
        initialShares = cfg.getDouble("stock-market.initial-shares", 100_000.0)
        maxSingleTradeImpact = cfg.getDouble("stock-market.maximum-single-trade-price-impact", 0.10)
        minLiquidityFloor = cfg.getDouble("stock-market.minimum-liquidity-floor", 1000.0)
        serverStockCurveStrength = cfg.getDouble("stock-market.server-stock-curve-strength", 4.0).coerceAtLeast(0.0)
        serverStockCurveExponent = cfg.getDouble("stock-market.server-stock-curve-exponent", 1.6).coerceAtLeast(0.01)
        serverStockMinimumPrice = cfg.getDouble("stock-market.server-stock-minimum-price", 10.0)
        serverStockMaximumPrice = cfg.getDouble("stock-market.server-stock-maximum-price", 250_000.0)
            .coerceAtLeast(serverStockMinimumPrice)
        activityPeriodHours = cfg.getInt("stock-market.market-activity-period-hours", 24)
        largeTransactionThreshold = cfg.getDouble("stock-market.large-transaction-threshold", 500_000.0)
        chatInputTimeoutSeconds = cfg.getInt("stock-market.chat-input-timeout-seconds", 30)
        nameMinLength = cfg.getInt("stock-market.name-min-length", 3)
        nameMaxLength = cfg.getInt("stock-market.name-max-length", 24)
        protectedStocks = cfg.getStringList("stock-market.protected-stocks").map { it.trim().lowercase() }.toSet()

        activityVeryHigh = cfg.getDouble("stock-market.activity-thresholds.very-high", 1_000_000.0)
        activityHigh = cfg.getDouble("stock-market.activity-thresholds.high", 250_000.0)
        activityModerate = cfg.getDouble("stock-market.activity-thresholds.moderate", 50_000.0)
        activityLow = cfg.getDouble("stock-market.activity-thresholds.low", 5_000.0)
        trendStableBandPercent = cfg.getDouble("stock-market.trend-stable-band-percent", 2.0)

        val defaultIcons = listOf(
            "DIAMOND", "EMERALD", "PRISMARINE_CRYSTALS", "PRISMARINE_SHARD",
            "AMETHYST_SHARD", "ECHO_SHARD", "NETHER_STAR", "BLAZE_POWDER",
            "FIRE_CHARGE", "GHAST_TEAR", "HEART_OF_THE_SEA", "RABBIT_FOOT", "MAGMA_CREAM",
        )
        val configured = cfg.getStringList("stock-market.icon-materials")
        val names = if (configured.isNotEmpty()) configured else defaultIcons
        iconMaterials = names.mapNotNull { Material.matchMaterial(it) }.ifEmpty { listOf(Material.DIAMOND) }
    }

    /**
     * Old `bank_investments` (flat balance + 0.25%/hour compound interest) doesn't map onto
     * stock ownership, so we cash it out flat (no interest recompute — simplification, see
     * completion summary) into the real economy balance, then zero the row so a restart is
     * idempotent. The table itself is left in place (harmless if unused).
     */
    private fun migrateOldBankInvestments() {
        try {
            val rows = plugin.databaseManager.query(
                "SELECT uuid, balance FROM bank_investments WHERE balance > 0"
            ) { rs -> rs.getString("uuid") to rs.getDouble("balance") }

            if (rows.isEmpty()) return

            for ((uuidStr, balance) in rows) {
                try {
                    val uuid = UUID.fromString(uuidStr)
                    plugin.economyManager.deposit(uuid, balance)
                    plugin.databaseManager.execute("UPDATE bank_investments SET balance = 0 WHERE uuid = ?", uuidStr)
                } catch (e: Exception) {
                    plugin.logger.warning("[StockMarket] Failed to migrate bank_investments row for $uuidStr: ${e.message}")
                }
            }
            plugin.logger.info("[StockMarket] Migrated ${rows.size} old bank_investments balance(s) into the economy.")
        } catch (e: Exception) {
            // Table doesn't exist (fresh install) — nothing to migrate.
        }
    }

    /**
     * Creates the permanent JoshyMC server stock once, on first-ever start. A restart must
     * NOT reseed it — we only insert if the JOSH ticker doesn't already exist.
     */
    private fun ensureJoshyMcStock() {
        val existing = getStock(JOSH_TICKER)
        if (existing != null) return

        val icon = iconMaterials.randomOrNull() ?: Material.NETHER_STAR
        val now = System.currentTimeMillis() / 1000
        val seedCostBasis = defaultStockPrice * initialShares

        plugin.databaseManager.execute(
            "INSERT INTO stocks (ticker, name, name_lower, creator_uuid, icon, price, shares_outstanding, is_server_owned, created_at) VALUES (?,?,?,?,?,?,?,?,?)",
            JOSH_TICKER, JOSH_NAME, JOSH_NAME.lowercase(), null, icon.name, defaultStockPrice, initialShares, 1, now
        )
        plugin.databaseManager.execute(
            "INSERT INTO stock_holdings (ticker, uuid, shares, cost_basis) VALUES (?,?,?,?)",
            JOSH_TICKER, SERVER_STOCK_UUID_STRING, initialShares, seedCostBasis
        )

        plugin.logger.info("[StockMarket] Created the permanent JoshyMC ($JOSH_TICKER) server stock.")
    }

    // ── Row mapping ──────────────────────────────────────────────────

    private fun mapStock(rs: ResultSet): Stock {
        val iconName = rs.getString("icon")
        val icon = Material.matchMaterial(iconName) ?: Material.DIAMOND
        return Stock(
            ticker = rs.getString("ticker"),
            name = rs.getString("name"),
            nameLower = rs.getString("name_lower"),
            creatorUuid = rs.getString("creator_uuid"),
            icon = icon,
            price = rs.getDouble("price"),
            sharesOutstanding = rs.getDouble("shares_outstanding"),
            isServerOwned = rs.getInt("is_server_owned") != 0,
            createdAt = rs.getLong("created_at"),
        )
    }

    private fun mapHolding(rs: ResultSet): Holding = Holding(
        ticker = rs.getString("ticker"),
        uuid = rs.getString("uuid"),
        shares = rs.getDouble("shares"),
        costBasis = rs.getDouble("cost_basis"),
    )

    // ── Queries ──────────────────────────────────────────────────────

    fun getStock(ticker: String): Stock? =
        plugin.databaseManager.queryFirst("SELECT * FROM stocks WHERE ticker = ?", ticker) { mapStock(it) }

    fun getAllStocks(): List<Stock> =
        plugin.databaseManager.query("SELECT * FROM stocks") { mapStock(it) }

    fun getHolding(ticker: String, uuid: UUID): Holding? =
        plugin.databaseManager.queryFirst(
            "SELECT * FROM stock_holdings WHERE ticker = ? AND uuid = ?", ticker, uuid.toString()
        ) { mapHolding(it) }

    fun getHoldingsForPlayer(uuid: UUID): List<Holding> =
        plugin.databaseManager.query(
            "SELECT * FROM stock_holdings WHERE uuid = ? AND shares > ?", uuid.toString(), EPSILON
        ) { mapHolding(it) }

    fun getHoldingsForStock(ticker: String): List<Holding> =
        plugin.databaseManager.query(
            "SELECT * FROM stock_holdings WHERE ticker = ? AND shares > ?", ticker, EPSILON
        ) { mapHolding(it) }

    fun getHolderCount(ticker: String): Int =
        plugin.databaseManager.queryFirst(
            "SELECT COUNT(*) AS c FROM stock_holdings WHERE ticker = ? AND shares > ?", ticker, EPSILON
        ) { it.getInt("c") } ?: 0

    fun getMarketCap(stock: Stock): Double = StockPricingEngine.marketCap(stock.price, stock.sharesOutstanding)

    fun getMarketStats(stock: Stock): MarketStats {
        val periodStart = System.currentTimeMillis() / 1000 - activityPeriodHours * 3600L
        data class TradeRow(val isBuy: Boolean, val dollarAmount: Double, val price: Double)

        val trades = plugin.databaseManager.query(
            "SELECT is_buy, dollar_amount, price FROM stock_trades WHERE ticker = ? AND timestamp >= ? ORDER BY id ASC",
            stock.ticker, periodStart
        ) { rs -> TradeRow(rs.getInt("is_buy") == 1, rs.getDouble("dollar_amount"), rs.getDouble("price")) }

        val buyVolume = trades.filter { it.isBuy }.sumOf { it.dollarAmount }
        val sellVolume = trades.filter { !it.isBuy }.sumOf { it.dollarAmount }
        val volume = buyVolume + sellVolume

        val open = trades.firstOrNull()?.price ?: stock.price
        val prices = trades.map { it.price } + stock.price
        val high = prices.maxOrNull() ?: stock.price
        val low = prices.minOrNull() ?: stock.price
        val changePercent = if (open > 0.0) ((stock.price - open) / open) * 100.0 else 0.0

        val activity = StockPricingEngine.classifyActivity(volume, activityVeryHigh, activityHigh, activityModerate, activityLow)
        val trend = StockPricingEngine.classifyTrend(changePercent, trendStableBandPercent)

        return MarketStats(volume, buyVolume, sellVolume, open, high, low, changePercent, activity, trend)
    }

    /** Unrealized P/L (dollar, percent) for a holding at the stock's current price. */
    fun unrealizedPL(holding: Holding, currentPrice: Double): Pair<Double, Double> {
        val value = holding.shares * currentPrice
        val pl = value - holding.costBasis
        val plPercent = if (holding.costBasis > 0.0) (pl / holding.costBasis) * 100.0 else 0.0
        return pl to plPercent
    }

    // ── Ticker generation & name validation ─────────────────────────

    private fun tickerExists(ticker: String): Boolean =
        plugin.databaseManager.queryFirst("SELECT 1 FROM stocks WHERE ticker = ?", ticker) { true } != null

    fun generateTicker(name: String): String {
        val base = name.filter { it.isLetterOrDigit() }.uppercase().take(4).ifEmpty { "STCK" }
        if (!tickerExists(base)) return base
        var i = 1
        while (true) {
            val candidate = "$base$i"
            if (!tickerExists(candidate)) return candidate
            i++
        }
    }

    /** Returns an error message, or null if the name is valid & available. */
    fun validateStockName(rawName: String): String? {
        val name = rawName.trim()
        if (name.isEmpty()) return "Stock name cannot be empty."
        if (name.length < nameMinLength) return "Stock name must be at least $nameMinLength characters."
        if (name.length > nameMaxLength) return "Stock name must be at most $nameMaxLength characters."
        if (name.none { it.isLetterOrDigit() }) return "Stock name must contain at least one letter or number."
        if (ProfanityFilter.contains(name)) return "That stock name isn't allowed."

        val lower = name.lowercase()
        val existing = plugin.databaseManager.queryFirst(
            "SELECT 1 FROM stocks WHERE name_lower = ?", lower
        ) { true }
        if (existing != null) return "A stock with that name already exists."

        return null
    }

    /** Looks up a stock by exact ticker first, then by name (case-insensitive). */
    fun resolveStock(input: String): Stock? {
        val trimmed = input.trim()
        getStock(trimmed.uppercase())?.let { return it }
        return plugin.databaseManager.queryFirst(
            "SELECT * FROM stocks WHERE name_lower = ?", trimmed.lowercase()
        ) { mapStock(it) }
    }

    /** JOSH (server-owned) is always protected; config can add more via `protected-stocks`. */
    fun isProtectedStock(stock: Stock): Boolean =
        stock.isServerOwned || protectedStocks.contains(stock.ticker.lowercase()) || protectedStocks.contains(stock.nameLower)

    // ── Admin reset/delete flow ──────────────────────────────────────

    /**
     * Stage 1: validate + preview a reset/delete, stashing a pending confirmation keyed by
     * [adminKey] (player UUID string, or "CONSOLE"). Only the same key can confirm/cancel it.
     */
    fun prepareAdminAction(adminKey: String, actionType: AdminActionType, tickerOrName: String): AdminActionPreview {
        val stock = resolveStock(tickerOrName)
            ?: return AdminActionPreview.Failure("Stock '$tickerOrName' not found.")
        if (isProtectedStock(stock)) {
            return AdminActionPreview.Failure("${stock.name} (${stock.ticker}) is protected and cannot be ${actionType.verb}.")
        }

        val holdings = getHoldingsForStock(stock.ticker)
        val refundTotal = holdings.sumOf { it.costBasis }

        pendingAdminActions[adminKey] = PendingAdminAction(
            actionType, stock.ticker, System.currentTimeMillis() + chatInputTimeoutMs
        )
        return AdminActionPreview.Ready(stock, holdings.size, refundTotal)
    }

    fun cancelAdminAction(adminKey: String): PendingAdminAction? {
        val pending = pendingAdminActions.remove(adminKey) ?: return null
        if (isExpired(pending.expiresAt)) return null
        return pending
    }

    /**
     * Stage 2: revalidate against LIVE holdings/stock state (never the stale preview) and
     * execute atomically. Refunds use each holding's stored cost basis, never current market
     * price, so an admin action can't create artificial profit/loss. On any failure nothing
     * is changed — no partial refunds, no deleted holdings/stock.
     */
    fun confirmAdminAction(adminKey: String, adminName: String): AdminActionResult {
        val pending = pendingAdminActions.remove(adminKey)
            ?: return AdminActionResult.Failure("You have no pending stock action.")
        if (isExpired(pending.expiresAt)) {
            return AdminActionResult.Failure("Your pending stock action has expired.")
        }

        val stock = getStock(pending.ticker)
            ?: return AdminActionResult.Failure("That stock no longer exists.")
        if (isProtectedStock(stock)) {
            return AdminActionResult.Failure("${stock.name} (${stock.ticker}) is protected and cannot be ${pending.actionType.verb}.")
        }

        var investorsRefunded = 0
        var totalRefunded = 0.0
        val refundedUuids = mutableListOf<Pair<UUID, Double>>()
        try {
            plugin.databaseManager.transaction {
                val holdings = getHoldingsForStock(stock.ticker)
                for (holding in holdings) {
                    val refund = holding.costBasis
                    if (refund > 0.0) {
                        val uuid = UUID.fromString(holding.uuid)
                        plugin.economyManager.deposit(uuid, refund)
                        totalRefunded += refund
                        refundedUuids += uuid to refund
                    }
                    investorsRefunded++
                }

                plugin.databaseManager.execute("DELETE FROM stock_holdings WHERE ticker = ?", stock.ticker)
                plugin.databaseManager.execute("DELETE FROM stock_trades WHERE ticker = ?", stock.ticker)

                when (pending.actionType) {
                    AdminActionType.RESET -> plugin.databaseManager.execute(
                        "UPDATE stocks SET price = ?, shares_outstanding = ? WHERE ticker = ?",
                        defaultStockPrice, initialShares, stock.ticker
                    )
                    AdminActionType.DELETE -> plugin.databaseManager.execute(
                        "DELETE FROM stocks WHERE ticker = ?", stock.ticker
                    )
                }

                plugin.databaseManager.execute(
                    "INSERT INTO stock_admin_actions (ticker, name, action, admin_name, investors_refunded, total_refunded, timestamp) VALUES (?,?,?,?,?,?,?)",
                    stock.ticker, stock.name, pending.actionType.name, adminName,
                    investorsRefunded, totalRefunded, System.currentTimeMillis() / 1000
                )
            }
        } catch (e: Exception) {
            plugin.logger.severe(
                "[StockMarket] Admin ${pending.actionType.verb} of ${stock.ticker} by $adminName FAILED — no changes were made: ${e.message}"
            )
            return AdminActionResult.Failure("Action failed (${e.message ?: "unknown error"}). Nothing was changed.")
        }

        plugin.logger.info(
            "[StockMarket] $adminName ${pending.actionType.pastTense} stock ${stock.ticker} (${stock.name}) " +
                "— refunded $investorsRefunded investor(s) a total of ${plugin.economyManager.format(totalRefunded)}."
        )

        for ((uuid, refund) in refundedUuids) {
            Bukkit.getPlayer(uuid)?.let { online ->
                plugin.commsManager.send(
                    online,
                    Component.text(
                        "Your position in ${stock.name} (${stock.ticker}) was refunded ${plugin.economyManager.format(refund)} — the stock was ${pending.actionType.pastTense} by an admin.",
                        NamedTextColor.YELLOW
                    ),
                    CommunicationsManager.Category.ECONOMY
                )
            }
        }

        return AdminActionResult.Success(pending.actionType, stock.ticker, investorsRefunded, totalRefunded)
    }

    // ── Stock creation flow ─────────────────────────────────────────

    /**
     * Stage 1: validate + generate ticker, stash a pending confirmation for [player].
     * Returns an error message, or null on success (retrieve the staged name/ticker via
     * [getPendingCreation]).
     */
    fun prepareStockCreation(player: Player, rawName: String): String? {
        val error = validateStockName(rawName)
        if (error != null) return error

        if (!plugin.economyManager.has(player.uniqueId, stockCreationCost)) {
            return "You need ${plugin.economyManager.format(stockCreationCost)} to create a stock."
        }

        val name = rawName.trim()
        val ticker = generateTicker(name)
        pendingStockConfirmations[player.uniqueId] = PendingStockCreation(
            name, ticker, System.currentTimeMillis() + chatInputTimeoutMs
        )
        return null
    }

    fun getPendingCreation(uuid: UUID): PendingStockCreation? {
        val pending = pendingStockConfirmations[uuid] ?: return null
        if (isExpired(pending.expiresAt)) {
            pendingStockConfirmations.remove(uuid)
            return null
        }
        return pending
    }

    /** Stage 2: charge $creationCost and atomically create the stock. Never charges on failure. */
    fun finalizeStockCreation(player: Player): CreateOutcome {
        val pending = pendingStockConfirmations.remove(player.uniqueId)
            ?: return CreateOutcome.Failure("Your stock creation request expired. Please start again.")
        if (isExpired(pending.expiresAt)) {
            return CreateOutcome.Failure("Your stock creation request expired. Please start again.")
        }

        // Re-validate server-side — never trust GUI state alone.
        val error = validateStockName(pending.name)
        if (error != null) return CreateOutcome.Failure(error)
        if (!plugin.economyManager.has(player.uniqueId, stockCreationCost)) {
            return CreateOutcome.Failure("You need ${plugin.economyManager.format(stockCreationCost)} to create a stock.")
        }

        var created: Stock? = null
        try {
            plugin.databaseManager.transaction {
                if (!plugin.economyManager.withdraw(player.uniqueId, stockCreationCost)) {
                    throw IllegalStateException("insufficient_funds")
                }

                val icon = iconMaterials.randomOrNull() ?: Material.DIAMOND
                val now = System.currentTimeMillis() / 1000

                plugin.databaseManager.execute(
                    "INSERT INTO stocks (ticker, name, name_lower, creator_uuid, icon, price, shares_outstanding, is_server_owned, created_at) VALUES (?,?,?,?,?,?,?,?,?)",
                    pending.ticker, pending.name, pending.name.lowercase(), player.uniqueId.toString(),
                    icon.name, defaultStockPrice, initialShares, 0, now
                )
                plugin.databaseManager.execute(
                    "INSERT INTO stock_holdings (ticker, uuid, shares, cost_basis) VALUES (?,?,?,?)",
                    pending.ticker, player.uniqueId.toString(), initialShares, stockCreationCost
                )
                plugin.databaseManager.execute(
                    "INSERT INTO stock_trades (ticker, uuid, is_buy, dollar_amount, shares, price, server_triggered, timestamp) VALUES (?,?,?,?,?,?,?,?)",
                    pending.ticker, player.uniqueId.toString(), 1, stockCreationCost, initialShares, defaultStockPrice, 0, now
                )

                created = Stock(
                    pending.ticker, pending.name, pending.name.lowercase(), player.uniqueId.toString(),
                    icon, defaultStockPrice, initialShares, false, now
                )
            }
        } catch (e: Exception) {
            return CreateOutcome.Failure("Stock creation failed (${e.message ?: "unknown error"}). You have not been charged.")
        }

        return CreateOutcome.Success(created!!)
    }

    // ── Buy/Sell precondition checks (shared by orchestration + GUI previews) ─

    fun checkBuyAmount(player: Player, amount: Double): String? {
        if (!StockPricingEngine.isSafePositiveAmount(amount)) return "Invalid amount."
        if (amount < minimumBuy) return "Minimum purchase is ${plugin.economyManager.format(minimumBuy)}."
        if (!plugin.economyManager.has(player.uniqueId, amount)) return "You don't have enough money."
        return null
    }

    fun checkSellAmount(holding: Holding?, amount: Double): String? {
        if (holding == null || holding.shares <= EPSILON) return "You don't own shares in this stock."
        if (!StockPricingEngine.isSafePositiveAmount(amount)) return "Invalid amount."
        return null
    }

    // ── Buy/Sell orchestration ───────────────────────────────────────

    /** Pricing-engine-agnostic result of a buy leg, shared by both [StockPricingEngine] and [ServerStockPricingEngine]. */
    data class BuyLegResult(
        val avgExecutionPrice: Double,
        val sharesMinted: Double,
        val newPrice: Double,
        val newSharesOutstanding: Double,
    )

    /** Pricing-engine-agnostic result of a sell leg, shared by both [StockPricingEngine] and [ServerStockPricingEngine]. */
    data class SellLegResult(
        val avgExecutionPrice: Double,
        val sharesRemoved: Double,
        val newPrice: Double,
        val newSharesOutstanding: Double,
        val realizedPL: Double,
        val costBasisRemoved: Double,
    )

    /**
     * Pure (no persistence) buy computation for [stock], routed to the pricing engine that
     * matches [Stock.isServerOwned]. Used both by the real trade execution below and by GUI
     * previews, so the two never drift apart.
     */
    fun computeBuyLeg(stock: Stock, dollarAmount: Double): BuyLegResult {
        return if (stock.isServerOwned) {
            val r = ServerStockPricingEngine.computeBuy(
                stock.sharesOutstanding, dollarAmount, defaultStockPrice,
                serverStockCurveStrength, serverStockCurveExponent, serverStockMinimumPrice, serverStockMaximumPrice,
            )
            BuyLegResult(r.avgExecutionPrice, r.sharesMinted, r.newPrice, r.newSharesOutstanding)
        } else {
            val r = StockPricingEngine.computeBuy(stock.price, stock.sharesOutstanding, dollarAmount, maxSingleTradeImpact, minLiquidityFloor)
            BuyLegResult(r.avgExecutionPrice, r.sharesMinted, r.newPrice, r.newSharesOutstanding)
        }
    }

    /** Pure (no persistence) sell computation — see [computeBuyLeg]. */
    fun computeSellLeg(stock: Stock, holderShares: Double, holderCostBasis: Double, dollarAmount: Double): SellLegResult {
        return if (stock.isServerOwned) {
            val r = ServerStockPricingEngine.computeSell(
                stock.sharesOutstanding, dollarAmount, holderShares, holderCostBasis, defaultStockPrice,
                serverStockCurveStrength, serverStockCurveExponent, serverStockMinimumPrice, serverStockMaximumPrice,
            )
            SellLegResult(r.avgExecutionPrice, r.sharesRemoved, r.newPrice, r.newSharesOutstanding, r.realizedPL, r.costBasisRemoved)
        } else {
            val r = StockPricingEngine.computeSell(
                stock.price, stock.sharesOutstanding, dollarAmount, holderShares, holderCostBasis, maxSingleTradeImpact, minLiquidityFloor
            )
            SellLegResult(r.avgExecutionPrice, r.sharesRemoved, r.newPrice, r.newSharesOutstanding, r.realizedPL, r.costBasisRemoved)
        }
    }

    private fun executeBuyLeg(uuid: UUID, stock: Stock, dollarAmount: Double): Pair<Stock, BuyLegResult> {
        val holding = getHolding(stock.ticker, uuid)
        val result = computeBuyLeg(stock, dollarAmount)

        plugin.databaseManager.execute(
            "UPDATE stocks SET price = ?, shares_outstanding = ? WHERE ticker = ?",
            result.newPrice, result.newSharesOutstanding, stock.ticker
        )

        val newShares = (holding?.shares ?: 0.0) + result.sharesMinted
        val newCostBasis = (holding?.costBasis ?: 0.0) + dollarAmount
        plugin.databaseManager.execute(
            "INSERT INTO stock_holdings (ticker, uuid, shares, cost_basis) VALUES (?,?,?,?) " +
                "ON CONFLICT(ticker, uuid) DO UPDATE SET shares = ?, cost_basis = ?",
            stock.ticker, uuid.toString(), newShares, newCostBasis, newShares, newCostBasis
        )

        plugin.databaseManager.execute(
            "INSERT INTO stock_trades (ticker, uuid, is_buy, dollar_amount, shares, price, server_triggered, timestamp) VALUES (?,?,?,?,?,?,?,?)",
            stock.ticker, uuid.toString(), 1, dollarAmount, result.sharesMinted, result.avgExecutionPrice,
            0, System.currentTimeMillis() / 1000
        )

        return stock.copy(price = result.newPrice, sharesOutstanding = result.newSharesOutstanding) to result
    }

    private fun executeSellLeg(uuid: UUID, stock: Stock, dollarAmount: Double): Pair<Stock, SellLegResult> {
        val holding = getHolding(stock.ticker, uuid) ?: Holding(stock.ticker, uuid.toString(), 0.0, 0.0)
        val result = computeSellLeg(stock, holding.shares, holding.costBasis, dollarAmount)

        plugin.databaseManager.execute(
            "UPDATE stocks SET price = ?, shares_outstanding = ? WHERE ticker = ?",
            result.newPrice, result.newSharesOutstanding, stock.ticker
        )

        val newShares = (holding.shares - result.sharesRemoved).coerceAtLeast(0.0)
        val newCostBasis = (holding.costBasis - result.costBasisRemoved).coerceAtLeast(0.0)
        plugin.databaseManager.execute(
            "INSERT INTO stock_holdings (ticker, uuid, shares, cost_basis) VALUES (?,?,?,?) " +
                "ON CONFLICT(ticker, uuid) DO UPDATE SET shares = ?, cost_basis = ?",
            stock.ticker, uuid.toString(), newShares, newCostBasis, newShares, newCostBasis
        )

        plugin.databaseManager.execute(
            "INSERT INTO stock_trades (ticker, uuid, is_buy, dollar_amount, shares, price, server_triggered, timestamp) VALUES (?,?,?,?,?,?,?,?)",
            stock.ticker, uuid.toString(), 0, dollarAmount, result.sharesRemoved, result.avgExecutionPrice,
            0, System.currentTimeMillis() / 1000
        )

        return stock.copy(price = result.newPrice, sharesOutstanding = result.newSharesOutstanding) to result
    }

    /**
     * Buy [dollarAmount] worth of [ticker] for [player]. Fully atomic: on any failure the
     * player is not charged and no shares are minted. Pays only the normal calculated
     * transaction value — no server-funded contribution or hidden adjustment on either side.
     */
    fun buyStock(player: Player, ticker: String, dollarAmount: Double): TradeOutcome {
        val stock = getStock(ticker) ?: return TradeOutcome.Failure("That stock no longer exists.")
        checkBuyAmount(player, dollarAmount)?.let { return TradeOutcome.Failure(it) }

        var outcome: TradeOutcome = TradeOutcome.Failure("Transaction failed.")
        try {
            plugin.databaseManager.transaction {
                if (!plugin.economyManager.withdraw(player.uniqueId, dollarAmount)) {
                    throw IllegalStateException("insufficient_funds")
                }

                val (finalStock, result) = executeBuyLeg(player.uniqueId, stock, dollarAmount)

                outcome = TradeOutcome.BuySuccess(
                    finalStock, dollarAmount, result.sharesMinted, result.avgExecutionPrice, stock.price, finalStock.price
                )
            }
        } catch (e: Exception) {
            return TradeOutcome.Failure("Transaction failed (${e.message ?: "unknown error"}). You have not been charged.")
        }
        return outcome
    }

    /**
     * Sell [dollarAmountRequested] worth of [ticker] for [player] (clamped to their current
     * holding value). Fully atomic. Credits only the normal calculated sale value — no
     * server-funded deduction or hidden adjustment on either side.
     */
    fun sellStock(player: Player, ticker: String, dollarAmountRequested: Double): TradeOutcome {
        val stock = getStock(ticker) ?: return TradeOutcome.Failure("That stock no longer exists.")
        val holding = getHolding(ticker, player.uniqueId)
        checkSellAmount(holding, dollarAmountRequested)?.let { return TradeOutcome.Failure(it) }
        holding!!

        val maxValue = holding.shares * stock.price
        val dollarAmount = dollarAmountRequested.coerceAtMost(maxValue)
        if (dollarAmount <= 0.0) return TradeOutcome.Failure("Invalid amount.")

        var outcome: TradeOutcome = TradeOutcome.Failure("Transaction failed.")
        try {
            plugin.databaseManager.transaction {
                val (finalStock, result) = executeSellLeg(player.uniqueId, stock, dollarAmount)

                // Credit real proceeds to the player. sharesRemoved * avgExecutionPrice equals
                // dollarAmount unless the sell was clamped to the holder's remaining shares.
                val proceeds = result.sharesRemoved * result.avgExecutionPrice
                plugin.economyManager.deposit(player.uniqueId, proceeds)

                val remainingHolding = getHolding(ticker, player.uniqueId)
                val remainingShares = remainingHolding?.shares ?: 0.0

                outcome = TradeOutcome.SellSuccess(
                    finalStock, dollarAmount, result.sharesRemoved, result.avgExecutionPrice,
                    stock.price, finalStock.price, result.realizedPL, remainingShares, remainingShares * finalStock.price
                )
            }
        } catch (e: Exception) {
            return TradeOutcome.Failure("Transaction failed (${e.message ?: "unknown error"}). You have not been charged.")
        }
        return outcome
    }

    // ── Sort types for the Trading GUI ──────────────────────────────

    enum class SortMode(val displayName: String) {
        MOST_HOLDERS("Most Holders"),
        MOST_ACTIVE("Most Active"),
        HIGHEST_MARKET_CAP("Highest Market Cap"),
        LEAST_ACTIVE("Least Active");

        fun next(): SortMode = entries[(ordinal + 1) % entries.size]
    }
}
