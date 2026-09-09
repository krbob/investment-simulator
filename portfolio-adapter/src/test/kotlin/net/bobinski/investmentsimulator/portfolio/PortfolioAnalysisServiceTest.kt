package net.bobinski.investmentsimulator.portfolio

import java.math.BigDecimal
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.bobinski.investmentsimulator.engine.CashFlow
import net.bobinski.investmentsimulator.engine.ComparisonRequest
import net.bobinski.investmentsimulator.engine.MonthlyPlan
import net.bobinski.investmentsimulator.engine.OpeningTaxState
import net.bobinski.investmentsimulator.engine.SimulationEngine
import net.bobinski.investmentsimulator.engine.TaxKind
import net.bobinski.investmentsimulator.engine.TaxLiability
import net.bobinski.investmentsimulator.engine.WithdrawalOrder
import net.bobinski.investmentsimulator.engine.YearAssumptions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PortfolioAnalysisServiceTest {
    @Test
    fun `missing tax state remains unknown on January first despite a complete ledger`() {
        val request = fixture()
        val result = PortfolioAnalysisService.analyze(request.copy(
            portfolio = request.portfolio.copy(openingTaxState = null),
        ))

        assertEquals(PortfolioAnalysisStatus.NEEDS_INPUT, result.status)
        assertGap(result, PortfolioDataGapCode.MISSING_TAX_STATE)
    }

    @Test
    fun `explicit empty tax state means verified zero and requires the simulation start date`() {
        val request = fixture().let {
            it.copy(portfolio = it.portfolio.copy(openingTaxState = Json.decodeFromString<OpeningTaxState>("{}")))
        }
        val complete = PortfolioAnalysisService.analyze(request)
        assertEquals(PortfolioAnalysisStatus.COMPLETE, complete.status)
        assertEquals(OpeningTaxState(), requireNotNull(complete.resolvedRequest).initial.taxState)

        listOf(null, "2026-12-31", "2027-01-02").forEach { asOf ->
            val incomplete = PortfolioAnalysisService.analyze(request.copy(plan = request.plan.copy(taxStateAsOfDate = asOf)))
            assertGap(incomplete, PortfolioDataGapCode.TAX_STATE_DATE_MISMATCH)
        }
    }

    @Test
    fun `verified prior year liability survives January first import and gets paid`() {
        val request = fixture()
        val liability = TaxLiability(TaxKind.CAPITAL_GAINS, 2026, "2027-04-30", BigDecimal("40"))
        val result = PortfolioAnalysisService.analyze(request.copy(
            portfolio = request.portfolio.copy(openingTaxState = OpeningTaxState(liabilities = listOf(liability))),
        ))

        assertEquals(PortfolioAnalysisStatus.COMPLETE, result.status)
        assertEquals(listOf(liability), requireNotNull(result.resolvedRequest).initial.taxState?.liabilities)
        money("40", requireNotNull(result.comparison).results.single { it.strategyId == "keep-taxable" }.capitalGainsTaxPaidPln)
    }

    @Test
    fun `prior year realized gains and OKI values cannot become January first year to date state`() {
        val request = fixture()
        listOf(
            OpeningTaxState(realizedGainPln = BigDecimal("210")),
            OpeningTaxState(okiValueDaysPln = BigDecimal("1000")),
        ).forEach { state ->
            val result = PortfolioAnalysisService.analyze(request.copy(portfolio = request.portfolio.copy(openingTaxState = state)))
            assertGap(result, PortfolioDataGapCode.TAX_STATE_DATE_MISMATCH)
        }
    }

    @Test
    fun `reports all foreign purchase cost gaps including the fully sold FIFO lot`() {
        val request = foreignFixture()
        val result = PortfolioAnalysisService.analyze(request)
        val purchaseIds = request.portfolio.snapshot.transactions.filter { it.type == "BUY" }.map { it.id }.toSet()
        val gaps = result.dataGaps.filter { it.code == PortfolioDataGapCode.MISSING_VERIFIED_PURCHASE_COST }

        assertEquals(purchaseIds, gaps.map { it.transactionId }.toSet())
        assertEquals(2, gaps.size)
        assertTrue(gaps.all { it.path.isNotBlank() && it.message.isNotBlank() })
        assertGap(result, PortfolioDataGapCode.MISSING_VERIFIED_PURCHASE_COST)
    }

    @Test
    fun `only absent override is reported and original verified costs flow through partial FIFO sale`() {
        val request = foreignFixture()
        val purchases = request.portfolio.snapshot.transactions.filter { it.type == "BUY" }
        val costs = listOf(
            PurchaseCostOverride(purchases[0].id, BigDecimal("200"), "Synthetic verified tax purchase record A"),
            PurchaseCostOverride(purchases[1].id, BigDecimal("500"), "Synthetic verified tax purchase record B"),
        )
        val partial = PortfolioAnalysisService.analyze(request.copy(
            portfolio = request.portfolio.copy(verifiedPurchaseCostsPln = costs.take(1)),
        ))
        assertEquals(listOf(purchases[1].id), partial.dataGaps
            .filter { it.code == PortfolioDataGapCode.MISSING_VERIFIED_PURCHASE_COST }.map { it.transactionId })
        assertGap(partial, PortfolioDataGapCode.MISSING_VERIFIED_PURCHASE_COST)

        val complete = PortfolioAnalysisService.analyze(request.copy(
            portfolio = request.portfolio.copy(verifiedPurchaseCostsPln = costs),
        ))
        assertEquals(PortfolioAnalysisStatus.COMPLETE, complete.status)
        money("300", requireNotNull(complete.resolvedRequest).initial.taxableLots.single().costBasisPln)
        assertEquals(costs, requireNotNull(complete.source).acquisitionCostSources)
    }

    @Test
    fun `nonzero purchase tax needs a verified cost even on a PLN trade`() {
        val request = fixture()
        val purchase = request.portfolio.snapshot.transactions.first { it.type == "BUY" }
        val result = PortfolioAnalysisService.analyze(request.copy(portfolio = request.portfolio.copy(
            snapshot = request.portfolio.snapshot.copy(transactions = request.portfolio.snapshot.transactions.map {
                if (it.id == purchase.id) it.copy(taxAmount = BigDecimal.ONE) else it
            }),
        )))
        assertEquals(listOf(purchase.id), result.dataGaps
            .filter { it.code == PortfolioDataGapCode.MISSING_VERIFIED_PURCHASE_COST }.map { it.transactionId })
        assertGap(result, PortfolioDataGapCode.MISSING_VERIFIED_PURCHASE_COST)
    }

    @Test
    fun `strict carry requires both previous day capture and previous day quotes`() {
        val request = fixture()
        val oldCapture = request.portfolio.copy(
            snapshot = request.portfolio.snapshot.copy(exportedAt = "2026-12-30T12:00:00Z"),
        )
        val oldQuote = request.portfolio.copy(holdings = request.portfolio.holdings.map { it.copy(valuedAt = "2026-12-30") })
        listOf(oldCapture, oldQuote).forEach { portfolio ->
            assertGap(PortfolioAnalysisService.analyze(request.copy(portfolio = portfolio)),
                PortfolioDataGapCode.SNAPSHOT_START_MISMATCH)
        }
        assertEquals(PortfolioAnalysisStatus.COMPLETE, PortfolioAnalysisService.analyze(request).status)
    }

    @Test
    fun `source date uses Warsaw day at a UTC year boundary`() {
        val request = fixture()
        val result = PortfolioAnalysisService.analyze(request.copy(portfolio = request.portfolio.copy(
            // Already January 1 in Warsaw: the capture cannot be the opening state of that day.
            snapshot = request.portfolio.snapshot.copy(exportedAt = "2026-12-31T23:30:00Z"),
        )))
        assertGap(result, PortfolioDataGapCode.SNAPSHOT_START_MISMATCH)
    }

    @Test
    fun `explicit unchanged values policy accepts future start with visible assumptions`() {
        val request = fixture()
        val earlier = request.portfolio.copy(
            snapshot = request.portfolio.snapshot.copy(exportedAt = "2026-09-09T12:00:00Z"),
            holdings = request.portfolio.holdings.map { it.copy(valuedAt = "2026-09-09") },
        )
        assertGap(PortfolioAnalysisService.analyze(request.copy(portfolio = earlier)),
            PortfolioDataGapCode.SNAPSHOT_START_MISMATCH)
        val result = PortfolioAnalysisService.analyze(request.copy(
            portfolio = earlier,
            plan = request.plan.copy(openingValuationPolicy = OpeningValuationPolicy.USE_CAPTURED_VALUES_UNCHANGED),
        ))

        assertEquals(PortfolioAnalysisStatus.COMPLETE, result.status)
        money("120", requireNotNull(result.resolvedRequest).initial.taxableLots.single().marketValuePln)
        assertEquals("2026-09-09", requireNotNull(result.source).sourceAsOfDate)
        assertTrue(result.assumptions.isNotEmpty())
    }

    @Test
    fun `unchanged values policy never allows backward or same day simulation starts`() {
        val request = fixture()
        listOf("2027-01-01T12:00:00Z", "2027-01-02T12:00:00Z").forEach { exportedAt ->
            val result = PortfolioAnalysisService.analyze(request.copy(
                portfolio = request.portfolio.copy(snapshot = request.portfolio.snapshot.copy(exportedAt = exportedAt)),
                plan = request.plan.copy(openingValuationPolicy = OpeningValuationPolicy.USE_CAPTURED_VALUES_UNCHANGED),
            ))
            assertGap(result, PortfolioDataGapCode.SNAPSHOT_START_MISMATCH)
        }
    }

    @Test
    fun `loss carryforwards and partial final year are explicit unsupported capabilities`() {
        val request = fixture()
        val result = PortfolioAnalysisService.analyze(request.copy(plan = request.plan.copy(
            lossCarryforwardPln = BigDecimal("1000"), endDate = "2027-06-30",
        )))
        assertEquals(PortfolioAnalysisStatus.UNSUPPORTED, result.status)
        assertGap(result, PortfolioDataGapCode.UNSUPPORTED_LOSS_CARRYFORWARD)
        assertGap(result, PortfolioDataGapCode.UNSUPPORTED_FINAL_PARTIAL_YEAR)
    }

    @Test
    fun `invalid scenario configuration is returned without throwing or recommending`() {
        val request = fixture()
        listOf(
            request.plan.copy(startDate = "not-a-date"),
            request.plan.copy(assumptions = emptyList()),
            request.plan.copy(tradingFeeRate = -0.01),
            request.plan.copy(monthlyPlan = MonthlyPlan(dayOfMonth = 31)),
        ).forEach { plan ->
            assertGap(PortfolioAnalysisService.analyze(request.copy(plan = plan)), PortfolioDataGapCode.INVALID_SCENARIO)
        }
    }

    @Test
    fun `malformed nested plan and liability dates return structured scenario gaps`() {
        val request = fixture()
        val invalid = listOf(
            request.copy(plan = request.plan.copy(monthlyPlan = MonthlyPlan(withdrawalFrom = "2027-02-30"))),
            request.copy(plan = request.plan.copy(monthlyPlan = MonthlyPlan(contributionUntil = "not-a-date"))),
            request.copy(plan = request.plan.copy(cashFlows = listOf(CashFlow("2027-13-01")))),
            request.copy(portfolio = request.portfolio.copy(openingTaxState = OpeningTaxState(liabilities = listOf(
                TaxLiability(TaxKind.CAPITAL_GAINS, 2026, "2027-04-31", BigDecimal("40")),
            )))),
        )
        invalid.forEach { input ->
            assertGap(PortfolioAnalysisService.analyze(input), PortfolioDataGapCode.INVALID_SCENARIO)
        }
    }

    @Test
    fun `malformed source timestamps trade dates and quote dates return structured snapshot gaps`() {
        val request = fixture()
        val portfolio = request.portfolio
        val malformedSources = listOf(
            portfolio.copy(snapshot = portfolio.snapshot.copy(exportedAt = "not-an-instant")),
            portfolio.copy(snapshot = portfolio.snapshot.copy(transactions = portfolio.snapshot.transactions.map {
                if (it.type == "SELL") it.copy(tradeDate = "2026-02-30") else it
            })),
            portfolio.copy(snapshot = portfolio.snapshot.copy(transactions = portfolio.snapshot.transactions.map {
                if (it.type == "SELL") it.copy(createdAt = "not-an-instant") else it
            })),
            portfolio.copy(holdings = portfolio.holdings.map { it.copy(valuedAt = "2026-13-01") }),
        )
        malformedSources.forEach { source ->
            // Permitting unchanged values must not bypass validation of the observation dates.
            val result = PortfolioAnalysisService.analyze(request.copy(
                portfolio = source,
                plan = request.plan.copy(openingValuationPolicy = OpeningValuationPolicy.USE_CAPTURED_VALUES_UNCHANGED),
            ))
            assertEquals(PortfolioAnalysisStatus.UNSUPPORTED, result.status)
            assertGap(result, PortfolioDataGapCode.UNSUPPORTED_SNAPSHOT)
        }
    }

    @Test
    fun `mapper scope and reconciliation failures become unsupported snapshot gaps`() {
        val request = fixture()
        listOf(
            request.portfolio.copy(holdings = request.portfolio.holdings.map { it.copy(valuationStatus = "STALE") }),
            request.portfolio.copy(accountSummaries = request.portfolio.accountSummaries.map {
                it.copy(cashBalancePln = it.cashBalancePln + BigDecimal.ONE)
            }),
            request.portfolio.copy(snapshot = request.portfolio.snapshot.copy(transactions = request.portfolio.snapshot.transactions.map {
                if (it.type == "SELL") it.copy(type = "CORRECTION") else it
            })),
        ).forEach { portfolio ->
            val result = PortfolioAnalysisService.analyze(request.copy(portfolio = portfolio))
            assertEquals(PortfolioAnalysisStatus.UNSUPPORTED, result.status)
            assertGap(result, PortfolioDataGapCode.UNSUPPORTED_SNAPSHOT)
        }
    }

    @Test
    fun `maps balances and plan into five comparable strategies and returns a replayable request`() {
        val request = fixture().let { it.copy(plan = it.plan.copy(
            monthlyPlan = MonthlyPlan(contributionPln = BigDecimal("100"), withdrawalPln = BigDecimal("30"),
                withdrawalFrom = "2027-06-01", indexContributions = true),
            cashFlows = listOf(CashFlow("2027-05-05", contributionPln = BigDecimal("250"))),
            withdrawalOrder = WithdrawalOrder.OKI_FIRST,
            tradingFeeRate = 0.001,
            minimumAdvantagePln = BigDecimal("2.50"),
            includeLedger = true,
        )) }
        val result = PortfolioAnalysisService.analyze(request)
        assertEquals(PortfolioAnalysisStatus.COMPLETE, result.status)
        assertTrue(result.dataGaps.isEmpty())
        val resolved = requireNotNull(result.resolvedRequest)
        money("120", resolved.initial.taxableLots.single().marketValuePln)
        money("60", resolved.initial.taxableLots.single().costBasisPln)
        money("1150", resolved.initial.cashPln)
        assertEquals(request.plan.startDate, resolved.startDate)
        assertEquals(request.plan.endDate, resolved.endDate)
        assertEquals(request.plan.assumptions, resolved.assumptions)
        assertEquals(request.plan.monthlyPlan, resolved.monthlyPlan)
        assertEquals(request.plan.cashFlows, resolved.cashFlows)
        assertEquals(request.plan.tradingFeeRate, resolved.tradingFeeRate)
        money("2.50", resolved.minimumAdvantagePln)
        assertTrue(resolved.includeLedger)
        assertEquals("keep-taxable", resolved.baselineStrategyId)
        val strategyIds = listOf("keep-taxable", "new-money-oki", "move-quarter-to-oki", "move-half-to-oki", "move-all-to-oki")
        assertEquals(strategyIds, resolved.strategies.map { it.id })
        assertEquals(strategyIds.toSet(), result.strategyDescriptions.map { it.id }.toSet())
        assertTrue(resolved.strategies.all { it.withdrawalOrder == WithdrawalOrder.OKI_FIRST })
        assertEquals(result.comparison, SimulationEngine.compare(Json.decodeFromString<ComparisonRequest>(Json.encodeToString(resolved))))
        assertNotNull(result.source)
    }

    @Test
    fun `cash only portfolio omits transfers that would move no holdings`() {
        val request = fixture()
        val portfolio = request.portfolio
        val result = PortfolioAnalysisService.analyze(request.copy(portfolio = portfolio.copy(
            snapshot = portfolio.snapshot.copy(transactions = portfolio.snapshot.transactions.take(1)),
            holdings = emptyList(),
            accountSummaries = portfolio.accountSummaries.map { it.copy(
                valuationState = "BOOK_ONLY", cashBalancePln = BigDecimal("1000"),
                cashBalances = listOf(PortfolioCurrencyBalance("PLN", BigDecimal("1000"))),
            ) },
        )))

        assertEquals(PortfolioAnalysisStatus.COMPLETE, result.status)
        assertEquals(listOf("keep-taxable", "new-money-oki"), requireNotNull(result.resolvedRequest).strategies.map { it.id })
        assertEquals(setOf("keep-taxable", "new-money-oki"), result.strategyDescriptions.map { it.id }.toSet())
    }

    @Test
    fun `full migration sells appreciated FIFO holdings and reports deferred Belka without losing principal`() {
        val base = fixture()
        val scale = BigDecimal("1000")
        val request = base.copy(portfolio = base.portfolio.copy(
            snapshot = base.portfolio.snapshot.copy(transactions = base.portfolio.snapshot.transactions.map {
                it.copy(grossAmount = it.grossAmount * scale, feeAmount = it.feeAmount * scale)
            }),
            holdings = base.portfolio.holdings.map { it.copy(currentValuePln = requireNotNull(it.currentValuePln) * scale) },
            accountSummaries = base.portfolio.accountSummaries.map { account -> account.copy(
                cashBalancePln = account.cashBalancePln * scale,
                cashBalances = account.cashBalances.map { it.copy(amount = it.amount * scale) },
            ) },
        ))
        val result = PortfolioAnalysisService.analyze(request)
        assertEquals(PortfolioAnalysisStatus.COMPLETE, result.status)
        val strategies = requireNotNull(result.comparison).results
        assertNull(strategies.single { it.strategyId == "keep-taxable" }.initialTransfer)
        assertNull(strategies.single { it.strategyId == "new-money-oki" }.initialTransfer)
        val migrated = strategies.single { it.strategyId == "move-all-to-oki" }
        val transfer = requireNotNull(migrated.initialTransfer)
        money("120000", transfer.grossSoldPln)
        money("60000", transfer.realizedGainPln)
        money("11400", transfer.estimatedAdditionalCapitalGainsTaxPln)
        money("120000", transfer.proceedsTransferredPln)
        money("120000", transfer.purchasedValuePln)
        money("0", migrated.capitalGainsTaxPaidPln)
        money("11400", migrated.yearly.single().capitalGainsTaxAssessedPln)
    }

    private fun fixture(): PortfolioAnalysisRequest {
        val source = Json.decodeFromString<PortfolioSnapshotRequest>(
            requireNotNull(javaClass.getResource("/portfolio-bundle.json")).readText(),
        )
        return PortfolioAnalysisRequest(
            portfolio = source.copy(
                snapshot = source.snapshot.copy(exportedAt = "2026-12-31T12:00:00Z"),
                holdings = source.holdings.map { it.copy(valuedAt = "2026-12-31") },
                openingTaxState = OpeningTaxState(),
            ),
            plan = PortfolioAnalysisPlan(
                startDate = "2027-01-01", endDate = "2027-12-31",
                assumptions = listOf(YearAssumptions(2027, 0.0, 0.0, 0.0085)),
                taxStateAsOfDate = "2027-01-01",
            ),
        )
    }

    private fun foreignFixture(): PortfolioAnalysisRequest {
        val request = fixture()
        val source = request.portfolio
        val transactions = source.snapshot.transactions.map { it.copy(currency = "USD", fxRateToPln = BigDecimal("4")) }
        val withdrawal = transactions.first().copy(
            id = "00000000-0000-0000-0000-000000000020", type = "WITHDRAWAL", tradeDate = "2026-04-01",
            grossAmount = BigDecimal("1150"),
        )
        return request.copy(portfolio = source.copy(
            snapshot = source.snapshot.copy(transactions = transactions + withdrawal),
            accountSummaries = source.accountSummaries.map { it.copy(cashBalancePln = BigDecimal.ZERO, cashBalances = emptyList()) },
        ))
    }

    private fun assertGap(result: PortfolioAnalysisResult, code: PortfolioDataGapCode) {
        assertTrue(result.dataGaps.any { it.code == code }, "Expected $code, got ${result.dataGaps}")
        assertNull(result.resolvedRequest, "Incomplete inputs must not produce a resolved comparison request")
        assertNull(result.comparison, "Incomplete inputs must not produce a recommendation")
    }

    private fun money(expected: String, actual: BigDecimal) {
        assertEquals(0, BigDecimal(expected).compareTo(actual), "Expected $expected, got $actual")
    }
}
