/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.fineract.portfolio.savings.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.fineract.infrastructure.businessdate.domain.BusinessDateType;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.organisation.monetary.domain.MonetaryCurrency;
import org.apache.fineract.organisation.monetary.domain.Money;
import org.apache.fineract.organisation.monetary.domain.MoneyHelper;
import org.apache.fineract.organisation.office.domain.Office;
import org.apache.fineract.portfolio.savings.SavingsAccountTransactionType;
import org.apache.fineract.portfolio.savings.exception.SavingsAccountTransactionNotFoundException;
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

    @Test
    void zeroInterestPivotBalanceUsesPostPivotLedgerEntriesWithoutDiscardingLifetimeTotals() {
        final SavingsAccount account = mock(SavingsAccount.class);
        final Office office = mock(Office.class);
        final SavingsAccountTransactionSummaryWrapper wrapper = new SavingsAccountTransactionSummaryWrapper();
        final SavingsAccountTransaction historicalDeposit = transaction(account, office, SavingsAccountTransactionType.DEPOSIT, "100.00");
        final SavingsAccountTransaction historicalWithdrawal = transaction(account, office, SavingsAccountTransactionType.WITHDRAWAL,
                "10.00");
        final SavingsAccountSummary summary = new SavingsAccountSummary();
        summary.reconcileTransactionDerivedSummary(CURRENCY, wrapper, List.of(historicalDeposit, historicalWithdrawal));
        summary.setRunningBalanceOnPivotDate(new BigDecimal("90.00"));

        final SavingsAccountTransaction deposit = transaction(account, office, SavingsAccountTransactionType.DEPOSIT, "20.00");
        final SavingsAccountTransaction dividend = transaction(account, office, SavingsAccountTransactionType.DIVIDEND_PAYOUT, "2.00");
        final SavingsAccountTransaction withdrawal = transaction(account, office, SavingsAccountTransactionType.WITHDRAWAL, "5.00");
        final SavingsAccountTransaction withdrawalFee = transaction(account, office, SavingsAccountTransactionType.WITHDRAWAL_FEE, "3.00");
        final SavingsAccountTransaction annualFee = transaction(account, office, SavingsAccountTransactionType.ANNUAL_FEE, "4.00");
        final SavingsAccountTransaction payCharge = feeCharge("7.00");
        final SavingsAccountTransaction interest = transaction(account, office, SavingsAccountTransactionType.INTEREST_POSTING, "1.00");
        final SavingsAccountTransaction overdraftInterest = transaction(account, office, SavingsAccountTransactionType.OVERDRAFT_INTEREST,
                "2.00");
        final SavingsAccountTransaction withholdTax = transaction(account, office, SavingsAccountTransactionType.WITHHOLD_TAX, "1.00");
        final SavingsAccountTransaction hold = SavingsAccountTransaction.holdAmount(account, office, null, TRANSACTION_DATE,
                Money.of(CURRENCY, new BigDecimal("11.00")), false);
        final SavingsAccountTransaction release = SavingsAccountTransaction.releaseAmount(hold, TRANSACTION_DATE);
        final SavingsAccountTransaction reversedDeposit = transaction(account, office, SavingsAccountTransactionType.DEPOSIT, "50.00");
        reversedDeposit.reverse();

        final List<SavingsAccountTransaction> monetaryTransactions = List.of(deposit, dividend, withdrawal, withdrawalFee, annualFee,
                payCharge, interest, overdraftInterest, withholdTax);
        monetaryTransactions.forEach(transaction -> summary.updateSummaryForZeroInterestPivot(CURRENCY, transaction));

        summary.updateAccountBalanceFromZeroInterestPivot(CURRENCY, List.of(deposit, dividend, withdrawal, withdrawalFee, annualFee,
                payCharge, interest, overdraftInterest, withholdTax, hold, release, reversedDeposit));
        summary.updateAccountBalanceFromZeroInterestPivot(CURRENCY, List.of(deposit, dividend, withdrawal, withdrawalFee, annualFee,
                payCharge, interest, overdraftInterest, withholdTax, hold, release, reversedDeposit));

        assertThat(summary.getAccountBalance()).isEqualByComparingTo("91.00");
        assertThat(summary.getTotalDeposits()).isEqualByComparingTo("122.00");
        assertThat(summary.getTotalWithdrawals()).isEqualByComparingTo("15.00");
        assertThat(summary.getTotalWithdrawalFees()).isEqualByComparingTo("3.00");
        assertThat(summary.getTotalAnnualFees()).isEqualByComparingTo("4.00");
        assertThat(summary.getTotalFeeCharge()).isEqualByComparingTo("7.00");
        assertThat(summary.getTotalInterestPosted()).isEqualByComparingTo("1.00");
        assertThat(summary.getTotalOverdraftInterestDerived()).isEqualByComparingTo("2.00");
        assertThat(summary.getTotalWithholdTax()).isEqualByComparingTo("1.00");
    }

    @Test
    void reversingPostPivotTransactionsRestoresLifetimeSummaryAndMakesReconciliationANoOp() {
        final SavingsAccount account = mock(SavingsAccount.class);
        final Office office = mock(Office.class);
        final SavingsAccountTransactionSummaryWrapper wrapper = new SavingsAccountTransactionSummaryWrapper();
        final SavingsAccountTransaction historicalDeposit = transaction(account, office, SavingsAccountTransactionType.DEPOSIT, "100.00");
        final SavingsAccountTransaction historicalWithdrawal = transaction(account, office, SavingsAccountTransactionType.WITHDRAWAL,
                "10.00");
        final List<SavingsAccountTransaction> history = List.of(historicalDeposit, historicalWithdrawal);
        final SavingsAccountSummary summary = new SavingsAccountSummary();
        summary.reconcileTransactionDerivedSummary(CURRENCY, wrapper, history);
        summary.setRunningBalanceOnPivotDate(new BigDecimal("90.00"));

        final SavingsAccountTransaction deposit = transaction(account, office, SavingsAccountTransactionType.DEPOSIT, "20.00");
        final SavingsAccountTransaction withdrawal = transaction(account, office, SavingsAccountTransactionType.WITHDRAWAL, "5.00");
        final SavingsAccountTransaction withdrawalFee = transaction(account, office, SavingsAccountTransactionType.WITHDRAWAL_FEE, "3.00");
        final SavingsAccountTransaction annualFee = transaction(account, office, SavingsAccountTransactionType.ANNUAL_FEE, "4.00");
        final SavingsAccountTransaction payCharge = feeCharge("7.00");
        final List<SavingsAccountTransaction> postPivotTransactions = List.of(deposit, withdrawal, withdrawalFee, annualFee, payCharge);

        postPivotTransactions.forEach(transaction -> summary.updateSummaryForZeroInterestPivot(CURRENCY, transaction));
        final List<SavingsAccountTransaction> allTransactions = List.of(historicalDeposit, historicalWithdrawal, deposit, withdrawal,
                withdrawalFee, annualFee, payCharge);

        assertThat(summary.getAccountBalance()).isEqualByComparingTo("91.00");
        assertThat(summary.getTotalWithdrawalFees()).isEqualByComparingTo("3.00");
        assertThat(summary.getTotalAnnualFees()).isEqualByComparingTo("4.00");
        assertThat(summary.getTotalFeeCharge()).isEqualByComparingTo("7.00");
        assertThat(summary.reconcileTransactionDerivedSummary(CURRENCY, wrapper, allTransactions)).isFalse();

        postPivotTransactions.forEach(transaction -> summary.reverseTransactionFromSummary(CURRENCY, transaction));
        deposit.reverse();
        withdrawal.reverse();
        withdrawalFee.reverse();
        annualFee.reverse();
        when(payCharge.isReversed()).thenReturn(true);
        when(payCharge.isFeeChargeAndNotReversed()).thenReturn(false);
        when(payCharge.isDebit()).thenReturn(false);
        summary.updateAccountBalanceFromZeroInterestPivot(CURRENCY, postPivotTransactions);

        assertThat(summary.getAccountBalance()).isEqualByComparingTo("90.00");
        assertThat(summary.getTotalDeposits()).isEqualByComparingTo("100.00");
        assertThat(summary.getTotalWithdrawals()).isEqualByComparingTo("10.00");
        assertThat(summary.getTotalWithdrawalFees()).isZero();
        assertThat(summary.getTotalAnnualFees()).isZero();
        assertThat(summary.getTotalFeeCharge()).isZero();
        assertThat(summary.reconcileTransactionDerivedSummary(CURRENCY, wrapper, allTransactions)).isFalse();
    }

    @Test
    void regularInterestPivotStillRebuildsSummaryFromProvidedTransactionSlice() {
        final SavingsAccount account = mock(SavingsAccount.class);
        final Office office = mock(Office.class);
        final SavingsAccountSummary summary = new SavingsAccountSummary();
        summary.setRunningBalanceOnPivotDate(new BigDecimal("90.00"));

        summary.updateSummaryWithPivotConfig(CURRENCY, new SavingsAccountTransactionSummaryWrapper(), null,
                List.of(transaction(account, office, SavingsAccountTransactionType.DEPOSIT, "20.00"),
                        transaction(account, office, SavingsAccountTransactionType.WITHDRAWAL, "5.00")));

        assertThat(summary.getAccountBalance()).isEqualByComparingTo("105.00");
        assertThat(summary.getTotalDeposits()).isEqualByComparingTo("20.00");
        assertThat(summary.getTotalWithdrawals()).isEqualByComparingTo("5.00");
    }

    @Test
    void regularInterestPivotRetainsItsExistingIncrementalFeeSemantics() {
        final SavingsAccount account = mock(SavingsAccount.class);
        final Office office = mock(Office.class);
        final SavingsAccountSummary summary = new SavingsAccountSummary();
        summary.setAccountBalance(new BigDecimal("100.00"));
        final SavingsAccountTransaction withdrawalFee = transaction(account, office, SavingsAccountTransactionType.WITHDRAWAL_FEE, "3.00");
        final SavingsAccountTransaction annualFee = transaction(account, office, SavingsAccountTransactionType.ANNUAL_FEE, "4.00");
        final SavingsAccountTransaction dividend = transaction(account, office, SavingsAccountTransactionType.DIVIDEND_PAYOUT, "2.00");

        summary.updateSummaryWithPivotConfig(CURRENCY, new SavingsAccountTransactionSummaryWrapper(), withdrawalFee,
                List.of(withdrawalFee));
        summary.updateSummaryWithPivotConfig(CURRENCY, new SavingsAccountTransactionSummaryWrapper(), annualFee, List.of(annualFee));
        summary.updateSummaryWithPivotConfig(CURRENCY, new SavingsAccountTransactionSummaryWrapper(), dividend, List.of(dividend));

        assertThat(summary.getAccountBalance()).isEqualByComparingTo("93.00");
        assertThat(summary.getTotalDeposits()).isNull();
        assertThat(summary.getTotalWithdrawalFees()).isEqualByComparingTo("3.00");
        assertThat(summary.getTotalAnnualFees()).isEqualByComparingTo("4.00");
        assertThat(summary.getTotalFeeCharge()).isEqualByComparingTo("7.00");
    }

    @Test
    void repeatedUndoDoesNotReverseTheAssociatedChargeTwice() {
        final SavingsAccount account = new SavingsAccount();
        account.currency = CURRENCY;
        final SavingsAccountTransaction transaction = mock(SavingsAccountTransaction.class);
        final SavingsAccountChargePaidBy paidBy = mock(SavingsAccountChargePaidBy.class);
        final SavingsAccountCharge charge = mock(SavingsAccountCharge.class);
        final AtomicBoolean reversed = new AtomicBoolean();
        when(transaction.getId()).thenReturn(1L);
        when(transaction.isIdentifiedBy(1L)).thenReturn(true);
        when(transaction.isReversed()).thenAnswer(invocation -> reversed.get());
        doAnswer(invocation -> {
            reversed.set(true);
            return null;
        }).when(transaction).reverse();
        when(transaction.getTransactionDate()).thenReturn(TRANSACTION_DATE);
        when(transaction.isChargeTransaction()).thenReturn(true);
        when(transaction.getAmount(CURRENCY)).thenReturn(Money.of(CURRENCY, new BigDecimal("7.00")));
        when(transaction.getSavingsAccountChargesPaid()).thenReturn(Set.of(paidBy));
        when(paidBy.getSavingsAccountCharge()).thenReturn(charge);
        account.setSavingsAccountTransactions(List.of(transaction));

        account.undoSavingsTransaction(1L);

        assertThatThrownBy(() -> account.undoSavingsTransaction(1L)).isInstanceOf(SavingsAccountTransactionNotFoundException.class);
        verify(charge, times(1)).undoPayment(eq(CURRENCY), any(Money.class));
    }

    private static SavingsAccountTransaction transaction(final SavingsAccount account, final Office office,
            final SavingsAccountTransactionType type, final String amount) {
        final Money money = Money.of(CURRENCY, new BigDecimal(amount));
        return switch (type) {
            case DEPOSIT -> SavingsAccountTransaction.deposit(account, office, null, TRANSACTION_DATE, money, "deposit");
            case WITHDRAWAL -> SavingsAccountTransaction.withdrawal(account, office, null, TRANSACTION_DATE, money, "withdrawal");
            case INTEREST_POSTING -> SavingsAccountTransaction.interestPosting(account, office, TRANSACTION_DATE, money, false);
            case WITHDRAWAL_FEE -> SavingsAccountTransaction.withdrawalFee(account, office, TRANSACTION_DATE, money, "withdrawal-fee");
            case ANNUAL_FEE -> SavingsAccountTransaction.annualFee(account, office, TRANSACTION_DATE, money);
            case DIVIDEND_PAYOUT -> SavingsAccountTransaction.deposit(account, office, null, TRANSACTION_DATE, money, type, "dividend");
            case OVERDRAFT_INTEREST -> SavingsAccountTransaction.overdraftInterest(account, office, TRANSACTION_DATE, money, false);
            case WITHHOLD_TAX -> SavingsAccountTransaction.withHoldTax(account, office, TRANSACTION_DATE, money, Map.of());
            default -> throw new IllegalArgumentException("Unsupported test transaction type: " + type);
        };
    }

    private static SavingsAccountTransaction feeCharge(final String amount) {
        final SavingsAccountTransaction transaction = mock(SavingsAccountTransaction.class);
        final BigDecimal transactionAmount = new BigDecimal(amount);
        when(transaction.getTransactionType()).thenReturn(SavingsAccountTransactionType.PAY_CHARGE);
        when(transaction.getAmount()).thenReturn(transactionAmount);
        when(transaction.getAmount(CURRENCY)).thenReturn(Money.of(CURRENCY, transactionAmount));
        when(transaction.isNotReversed()).thenReturn(true);
        when(transaction.isFeeChargeAndNotReversed()).thenReturn(true);
        when(transaction.isDebit()).thenReturn(true);
        return transaction;
    }
}
