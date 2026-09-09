package net.bobinski.investmentsimulator.portfolio

import java.math.BigDecimal
import kotlinx.serialization.json.Json
import net.bobinski.investmentsimulator.engine.OpeningTaxState
import net.bobinski.investmentsimulator.engine.ComparisonRequest
import net.bobinski.investmentsimulator.engine.SimulationEngine
import net.bobinski.investmentsimulator.engine.Strategy
import net.bobinski.investmentsimulator.engine.YearAssumptions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PortfolioSnapshotMapperTest {
    private fun fixture(): PortfolioSnapshotRequest = Json.decodeFromString(
        requireNotNull(javaClass.getResource("/portfolio-bundle.json")).readText()
    )

    @Test
    fun `reconstructs partial FIFO sale instead of consuming upstream average cost`() {
        val source = fixture()
        val result = PortfolioSnapshotMapper.map(source.copy(snapshot = source.snapshot.copy(
            transactions = source.snapshot.transactions.reversed()
        )))
        val lot = result.initial.taxableLots.single()
        assertEquals("00000000-0000-0000-0000-000000000012", lot.id)
        assertEquals("2026-02-10", lot.acquiredOn)
        money("60", lot.costBasisPln) // Upstream average-cost field is 42.00.
        money("120", lot.marketValuePln)
        money("1150", result.initial.cashPln)
        assertEquals("2026-09-09", result.source.sourceAsOfDate)
        assertNull(result.initial.taxState)
    }

    @Test
    fun `foreign accounting FX cannot silently establish a tax acquisition cost`() {
        val request = foreignFixture()
        val error = assertThrows(IllegalArgumentException::class.java) { PortfolioSnapshotMapper.map(request) }
        assertTrue(error.message.orEmpty().contains("verified PLN acquisition-cost"))
        val result = PortfolioSnapshotMapper.map(request.copy(verifiedPurchaseCostsPln = listOf(
            PurchaseCostOverride(request.snapshot.transactions[1].id, BigDecimal("200"), "Verified original purchase tax basis"),
            PurchaseCostOverride(request.snapshot.transactions[2].id, BigDecimal("500"), "Verified original purchase tax basis"),
        )))
        money("300", result.initial.taxableLots.single().costBasisPln)
        money("0", result.initial.cashPln)
        assertEquals(2, result.source.acquisitionCostSources.size)
    }

    @Test
    fun `rejects stale missing and inconsistent market snapshots`() {
        val request = fixture()
        listOf(
            request.holdings.single().copy(valuationStatus = "STALE"),
            request.holdings.single().copy(currentValuePln = null),
            request.holdings.single().copy(quantity = BigDecimal("4")),
            request.holdings.single().copy(valuedAt = "2026-09-10"),
            request.holdings.single().copy(valuedAt = "2026-02-28"),
        ).forEach { holding ->
            assertThrows(IllegalArgumentException::class.java) {
                PortfolioSnapshotMapper.map(request.copy(holdings = listOf(holding)))
            }
        }
    }

    @Test
    fun `rejects corrections other selected-account assets and oversells`() {
        val request = fixture()
        val sell = request.snapshot.transactions.last()
        listOf(
            sell.copy(type = "CORRECTION"),
            sell.copy(instrumentId = "00000000-0000-0000-0000-000000000099"),
            sell.copy(quantity = BigDecimal("16")),
        ).forEach { invalid ->
            val changed = request.snapshot.transactions.dropLast(1) + invalid
            assertThrows(IllegalArgumentException::class.java) {
                PortfolioSnapshotMapper.map(request.copy(snapshot = request.snapshot.copy(transactions = changed)))
            }
        }
    }

    @Test
    fun `reconciles native cash and forbids nonzero foreign cash`() {
        val request = fixture()
        val account = request.accountSummaries.single()
        listOf(
            account.copy(cashBalancePln = BigDecimal("1151")),
            account.copy(cashBalances = listOf(PortfolioCurrencyBalance("PLN", BigDecimal("1000")))),
            account.copy(cashBalances = account.cashBalances + PortfolioCurrencyBalance("USD", BigDecimal.ONE)),
        ).forEach { changed ->
            assertThrows(IllegalArgumentException::class.java) {
                PortfolioSnapshotMapper.map(request.copy(accountSummaries = listOf(changed)))
            }
        }
    }

    @Test
    fun `only passes caller verified opening tax state and keeps it out of ledger inference`() {
        val request = fixture()
        val taxState = OpeningTaxState(realizedGainPln = BigDecimal("987.65"))
        val result = PortfolioSnapshotMapper.map(request.copy(openingTaxState = taxState))
        assertEquals(taxState, result.initial.taxState)
        assertEquals(64, result.source.requestSha256.length)
        assertTrue(result.source.requestSha256 != PortfolioSnapshotMapper.map(request).source.requestSha256)
    }

    @Test
    fun `rejects duplicate IDs and unknown export schema`() {
        val request = fixture()
        listOf(
            request.snapshot.copy(schemaVersion = 6),
            request.snapshot.copy(transactions = request.snapshot.transactions + request.snapshot.transactions.first()),
        ).forEach { state ->
            assertThrows(IllegalArgumentException::class.java) {
                PortfolioSnapshotMapper.map(request.copy(snapshot = state))
            }
        }
    }

    @Test
    fun `preserves total market value across multiple unconsumed FIFO lots`() {
        val request = fixture()
        val result = PortfolioSnapshotMapper.map(request.copy(
            snapshot = request.snapshot.copy(transactions = request.snapshot.transactions.dropLast(1)),
            holdings = listOf(request.holdings.single().copy(quantity = BigDecimal("15"), currentValuePln = BigDecimal("100.01"))),
            accountSummaries = listOf(request.accountSummaries.single().copy(
                cashBalancePln = BigDecimal("790"),
                cashBalances = listOf(PortfolioCurrencyBalance("PLN", BigDecimal("790"))),
            )),
        ))
        assertEquals(2, result.initial.taxableLots.size)
        money("100.01", result.initial.taxableLots.sumOf { it.marketValuePln })
        money("210", result.initial.taxableLots.sumOf { it.costBasisPln })
        val comparison = SimulationEngine.compare(ComparisonRequest(
            startDate = "2027-01-01", endDate = "2027-01-02", initial = result.initial,
            assumptions = listOf(YearAssumptions(2027, 0.0, 0.0, 0.0085)),
            strategies = listOf(Strategy("baseline", 0.0)), baselineStrategyId = "baseline",
        ))
        assertEquals("baseline", comparison.baselineStrategyId)
    }

    @Test
    fun `maps selected OKI equity and rejects opening cash inside OKI`() {
        val request = fixture()
        val okiId = "00000000-0000-0000-0000-000000000050"
        val okiTransactions = request.snapshot.transactions.take(2).mapIndexed { index, tx -> tx.copy(
            id = "00000000-0000-0000-0000-00000000005${index + 1}", accountId = okiId,
            grossAmount = BigDecimal("110"), feeAmount = BigDecimal.ZERO,
        ) }
        val withOki = request.copy(
            selection = request.selection.copy(okiAccountId = okiId),
            okiOpenedOn = "2026-01-01",
            snapshot = request.snapshot.copy(
                accounts = request.snapshot.accounts + request.snapshot.accounts.single().copy(id = okiId),
                transactions = request.snapshot.transactions + okiTransactions,
            ),
            holdings = request.holdings + request.holdings.single().copy(accountId = okiId, quantity = BigDecimal("10")),
            accountSummaries = request.accountSummaries + request.accountSummaries.single().copy(
                accountId = okiId, cashBalancePln = BigDecimal.ZERO, cashBalances = emptyList(),
            ),
        )
        val result = PortfolioSnapshotMapper.map(withOki)
        money("120", result.initial.okiValuePln)
        assertEquals("2026-01-01", result.initial.okiOpenedOn)
        assertThrows(IllegalArgumentException::class.java) {
            PortfolioSnapshotMapper.map(withOki.copy(okiOpenedOn = null))
        }
        val withCash = withOki.copy(
            snapshot = withOki.snapshot.copy(transactions = withOki.snapshot.transactions.map {
                if (it.accountId == okiId && it.type == "DEPOSIT") it.copy(grossAmount = BigDecimal("111")) else it
            }),
            accountSummaries = withOki.accountSummaries.map {
                if (it.accountId == okiId) it.copy(cashBalancePln = BigDecimal.ONE,
                    cashBalances = listOf(PortfolioCurrencyBalance("PLN", BigDecimal.ONE))) else it
            },
        )
        val error = assertThrows(IllegalArgumentException::class.java) { PortfolioSnapshotMapper.map(withCash) }
        assertTrue(error.message.orEmpty().contains("Opening cash inside OKI"))
    }

    private fun foreignFixture(): PortfolioSnapshotRequest {
        val source = fixture()
        val transactions = source.snapshot.transactions.map { it.copy(currency = "USD", fxRateToPln = BigDecimal("4")) }
        val withdrawal = transactions.first().copy(
            id = "00000000-0000-0000-0000-000000000020", type = "WITHDRAWAL", tradeDate = "2026-04-01",
            grossAmount = BigDecimal("1150"),
        )
        return source.copy(
            snapshot = source.snapshot.copy(transactions = transactions + withdrawal),
            accountSummaries = listOf(source.accountSummaries.single().copy(cashBalancePln = BigDecimal.ZERO, cashBalances = emptyList())),
        )
    }

    private fun money(expected: String, actual: BigDecimal) {
        assertEquals(0, BigDecimal(expected).compareTo(actual), "Expected $expected, got $actual")
    }
}
