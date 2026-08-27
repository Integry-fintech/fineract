/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.fineract.portfolio.savings.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.fineract.infrastructure.businessdate.domain.BusinessDateType;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.organisation.monetary.domain.MonetaryCurrency;
import org.apache.fineract.organisation.monetary.domain.Money;
import org.apache.fineract.organisation.monetary.domain.MoneyHelper;
import org.apache.fineract.organisation.office.domain.Office;
import org.apache.fineract.portfolio.savings.SavingsAccountTransactionType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SavingsAccountBalanceReconciliationTest {

    private static final MonetaryCurrency CURRENCY = new MonetaryCurrency("COP", 2, null);
    private static final LocalDate TRANSACTION_DATE = LocalDate.of(2026, 8, 25);

    @BeforeEach
    void setUp() {
        ThreadLocalContextUtil.setTenant(new FineractPlatformTenant(1L, "default", "Default", "UTC", null));
        ThreadLocalContextUtil.setBusinessDates(new HashMap<>(Map.of(BusinessDateType.BUSINESS_DATE, TRANSACTION_DATE.plusDays(1))));
        MoneyHelper.initializeTenantRoundingMode("default", 6);
    }

    @AfterEach
    void tearDown() {
        ThreadLocalContextUtil.reset();
    }

    @Test
    void zeroInterestPivotDoesNotChangeTheMonetarySummary() {
        final SavingsAccountSummary summary = new SavingsAccountSummary();
        summary.setAccountBalance(new BigDecimal("59613.48"));
        final Office office = mock(Office.class);
        final SavingsAccount account = new SavingsAccount() {

            @Override
            public Office office() {
                return office;
            }

            @Override
            public void recalculateDailyBalances(final Money openingAccountBalance, final LocalDate interestPostingUpToDate,
                    final boolean backdatedTxnsAllowedTill, final boolean postReversals) {
                // The balance recalculation is independent from the summary preservation asserted by this test.
            }
        };
        account.currency = CURRENCY;
        account.summary = summary;

        final SavingsAccountTransaction pivot = account.postZeroInterestPivot(TRANSACTION_DATE);

        assertThat(summary.getAccountBalance()).isEqualByComparingTo("59613.48");
        assertThat(summary.getInterestPostedTillDate()).isEqualTo(TRANSACTION_DATE);
        assertThat(pivot.getAmount()).isZero();
        assertThat(pivot.isZeroInterestPivot()).isTrue();
    }

    @Test
    void reconciliationUsesLedgerEntryTypesAndIsIdempotent() {
        final SavingsAccount account = mock(SavingsAccount.class);
        final Office office = mock(Office.class);
        final SavingsAccountTransaction reversedDeposit = SavingsAccountTransaction.deposit(account, office, null, TRANSACTION_DATE,
                Money.of(CURRENCY, new BigDecimal("50.00")), "reversed");
        reversedDeposit.reverse();
        final SavingsAccountTransaction hold = SavingsAccountTransaction.holdAmount(account, office, null, TRANSACTION_DATE,
                Money.of(CURRENCY, new BigDecimal("11.00")), false);
        final SavingsAccountTransaction release = SavingsAccountTransaction.releaseAmount(hold, TRANSACTION_DATE);
        final List<SavingsAccountTransaction> transactions = List.of(
                SavingsAccountTransaction.deposit(account, office, null, TRANSACTION_DATE, Money.of(CURRENCY, new BigDecimal("100.00")),
                        "deposit"),
                SavingsAccountTransaction.deposit(account, office, null, TRANSACTION_DATE, Money.of(CURRENCY, new BigDecimal("5.00")),
                        SavingsAccountTransactionType.DIVIDEND_PAYOUT, "dividend"),
                SavingsAccountTransaction.interestPosting(account, office, TRANSACTION_DATE, Money.of(CURRENCY, new BigDecimal("10.00")),
                        false),
                SavingsAccountTransaction.withdrawal(account, office, null, TRANSACTION_DATE, Money.of(CURRENCY, new BigDecimal("20.00")),
                        "withdrawal"),
                SavingsAccountTransaction.withdrawalFee(account, office, TRANSACTION_DATE, Money.of(CURRENCY, new BigDecimal("3.00")),
                        "withdrawal-fee"),
                SavingsAccountTransaction.annualFee(account, office, TRANSACTION_DATE, Money.of(CURRENCY, new BigDecimal("4.00"))),
                SavingsAccountTransaction.charge(account, office, TRANSACTION_DATE, Money.of(CURRENCY, new BigDecimal("7.00"))),
                SavingsAccountTransaction.overdraftInterest(account, office, TRANSACTION_DATE, Money.of(CURRENCY, new BigDecimal("2.00")),
                        false),
                SavingsAccountTransaction.withHoldTax(account, office, TRANSACTION_DATE, Money.of(CURRENCY, new BigDecimal("1.00")),
                        Map.of()),
                reversedDeposit, hold, release);
        final SavingsAccountSummary summary = new SavingsAccountSummary();
        final SavingsAccountTransactionSummaryWrapper wrapper = new SavingsAccountTransactionSummaryWrapper();

        assertThat(summary.reconcileTransactionDerivedSummary(CURRENCY, wrapper, transactions)).isTrue();
        assertThat(summary.getTotalDeposits()).isEqualByComparingTo("105.00");
        assertThat(summary.getTotalWithdrawals()).isEqualByComparingTo("20.00");
        assertThat(summary.getTotalInterestPosted()).isEqualByComparingTo("10.00");
        assertThat(summary.getTotalWithdrawalFees()).isEqualByComparingTo("3.00");
        assertThat(summary.getTotalAnnualFees()).isEqualByComparingTo("4.00");
        assertThat(summary.getTotalOverdraftInterestDerived()).isEqualByComparingTo("2.00");
        assertThat(summary.getTotalWithholdTax()).isEqualByComparingTo("1.00");
        assertThat(summary.getAccountBalance()).isEqualByComparingTo("78.00");
        assertThat(summary.reconcileTransactionDerivedSummary(CURRENCY, wrapper, transactions)).isFalse();
    }
}
