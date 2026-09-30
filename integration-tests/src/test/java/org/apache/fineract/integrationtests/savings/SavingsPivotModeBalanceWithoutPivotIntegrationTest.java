/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.fineract.integrationtests.savings;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import org.apache.fineract.client.models.ChargeRequest;
import org.apache.fineract.client.models.GlobalConfigurationPropertyData;
import org.apache.fineract.client.models.PostSavingsAccountTransactionsRequest;
import org.apache.fineract.client.models.PostSavingsAccountsSavingsAccountIdChargesRequest;
import org.apache.fineract.client.models.PostSavingsAccountsSavingsAccountIdChargesSavingsAccountChargeIdRequest;
import org.apache.fineract.client.models.PostSavingsProductsRequest;
import org.apache.fineract.client.models.PutGlobalConfigurationsRequest;
import org.apache.fineract.client.models.SavingsAccountSummaryData;
import org.apache.fineract.client.models.SavingsAccountTransactionData;
import org.apache.fineract.infrastructure.configuration.api.GlobalConfigurationConstants;
import org.apache.fineract.integrationtests.common.ClientHelper;
import org.apache.fineract.integrationtests.common.Utils;
import org.apache.fineract.integrationtests.common.charges.ChargesHelper;
import org.apache.fineract.integrationtests.common.savings.SavingsAccountHelper;
import org.apache.fineract.integrationtests.savings.base.BaseSavingsIntegrationTest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * In pivot mode, an account that has no pivot yet loads its whole history on every write. The balance rebuilt from it
 * must keep the charges and fees already paid, whatever the write that follows them.
 */
public class SavingsPivotModeBalanceWithoutPivotIntegrationTest extends BaseSavingsIntegrationTest {

    private static final String ACTIVATION_DATE = "01 September 2026";
    private static final String TRANSACTION_DATE = "03 September 2026";

    @Test
    public void withdrawalAfterPaidChargeKeepsTheChargeInTheBalance() {
        inPivotMode(() -> {
            final Long chargeId = createFlatCharge("7.00");
            final Long savingsId = openActiveAccount();

            runAt(TRANSACTION_DATE, () -> {
                payNewCharge(savingsId, chargeId, "7.00");
                withdraw(savingsId, "100.00");

                assertSummary(savingsId, "893.00", "1000.00", "100.00", "7.00");
                assertLastRunningBalance(savingsId, "893.00");
            });
        });
    }

    @Test
    public void depositAfterPaidChargeKeepsTheChargeInTheBalance() {
        inPivotMode(() -> {
            final Long chargeId = createFlatCharge("7.00");
            final Long savingsId = openActiveAccount();

            runAt(TRANSACTION_DATE, () -> {
                payNewCharge(savingsId, chargeId, "7.00");
                deposit(savingsId, TRANSACTION_DATE, new BigDecimal("50.00"));

                assertSummary(savingsId, "1043.00", "1050.00", "0.00", "7.00");
                assertLastRunningBalance(savingsId, "1043.00");
            });
        });
    }

    @Test
    public void waiverAfterPaidChargeKeepsTheChargeInTheBalance() {
        inPivotMode(() -> {
            final Long chargeId = createFlatCharge("7.00");
            final Long savingsId = openActiveAccount();

            runAt(TRANSACTION_DATE, () -> {
                payNewCharge(savingsId, chargeId, "7.00");
                final Long waivedChargeId = addCharge(savingsId, chargeId, "7.00");
                ok(fineractClient().savingsAccountCharges.payOrWaiveSavingsAccountCharge(savingsId, waivedChargeId,
                        new PostSavingsAccountsSavingsAccountIdChargesSavingsAccountChargeIdRequest(), "waive"));

                assertSummary(savingsId, "993.00", "1000.00", "0.00", "7.00");
            });
        });
    }

    @Test
    public void consecutiveCashOutsKeepTheChargesInTheBalance() {
        inPivotMode(() -> {
            final Long chargeId = createFlatCharge("7.00");
            final Long savingsId = openActiveAccount();

            runAt(TRANSACTION_DATE, () -> {
                withdraw(savingsId, "100.00");
                assertBalance(savingsId, "900.00");
                payNewCharge(savingsId, chargeId, "7.00");
                assertBalance(savingsId, "893.00");
                withdraw(savingsId, "50.00");
                assertBalance(savingsId, "843.00");
                payNewCharge(savingsId, chargeId, "7.00");

                assertSummary(savingsId, "836.00", "1000.00", "150.00", "14.00");
                assertLastRunningBalance(savingsId, "836.00");
            });
        });
    }

    @Test
    public void undoOfAWithdrawalAfterPaidChargeKeepsTheChargeInTheBalance() {
        inPivotMode(() -> {
            final Long chargeId = createFlatCharge("7.00");
            final Long savingsId = openActiveAccount();

            runAt(TRANSACTION_DATE, () -> {
                final Long withdrawalId = withdraw(savingsId, "100.00");
                payNewCharge(savingsId, chargeId, "7.00");
                undo(savingsId, withdrawalId);

                assertSummary(savingsId, "993.00", "1000.00", "0.00", "7.00");
            });
        });
    }

    @Test
    public void undoOfOneOfTwoPaidChargesKeepsTheOtherInTheBalance() {
        inPivotMode(() -> {
            final Long chargeId = createFlatCharge("7.00");
            final Long smallChargeId = createFlatCharge("3.00");
            final Long savingsId = openActiveAccount();

            runAt(TRANSACTION_DATE, () -> {
                withdraw(savingsId, "100.00");
                payNewCharge(savingsId, chargeId, "7.00");
                payNewCharge(savingsId, smallChargeId, "3.00");
                assertBalance(savingsId, "890.00");
                undo(savingsId, lastTransactionId(savingsId));

                assertSummary(savingsId, "893.00", "1000.00", "100.00", "7.00");
            });
        });
    }

    @Test
    public void withdrawalFeesStayInTheBalance() {
        inPivotMode(() -> {
            // withdrawal fee of 3.00 on every withdrawal
            final Long withdrawalFeeId = new ChargesHelper().createCharges(new ChargeRequest().active(true)
                    .name(Utils.uniqueRandomStringGenerator("No_Pivot_Withdrawal_Fee_", 6)).currencyCode("USD").amount(3.0d)
                    .chargeAppliesTo(2).chargeTimeType(5).chargeCalculationType(1).locale("en").penalty(false)).getResourceId();
            final Long savingsId = openActiveAccount(id -> ok(fineractClient().savingsAccountCharges.createSavingsAccountCharge(id,
                    new PostSavingsAccountsSavingsAccountIdChargesRequest().chargeId(withdrawalFeeId).amount(3.0f).locale("en"))));

            runAt(TRANSACTION_DATE, () -> {
                deposit(savingsId, TRANSACTION_DATE, new BigDecimal("20.00"));
                withdraw(savingsId, "100.00");
                withdraw(savingsId, "50.00");

                assertSummary(savingsId, "864.00", "1020.00", "150.00", null);
                assertAmount("total withdrawal fees", "6.00", summary(savingsId).getTotalWithdrawalFees());
                // The running balance of a withdrawal and its fee can come out of order (already so on main, with or
                // without a pivot), so only the summary is asserted here.
            });
        });
    }

    /** Account with 1000.00 deposited on the activation date and no pivot. */
    private Long openActiveAccount() {
        return openActiveAccount(savingsId -> {});
    }

    private Long openActiveAccount(Consumer<Long> beforeActivation) {
        final Long clientId = clientHelper.createClient(ClientHelper.defaultClientCreationRequest()).getClientId();
        final Long productId = createProduct(zeroInterestProduct()).getResourceId();
        final Long savingsId = applySavingsAccount(applySavingsRequest(clientId, productId, ACTIVATION_DATE)).getSavingsId();
        runAt(ACTIVATION_DATE, () -> {
            beforeActivation.accept(savingsId);
            approveSavingsAccount(savingsId, ACTIVATION_DATE);
            activateSavingsAccount(savingsId, ACTIVATION_DATE);
            deposit(savingsId, ACTIVATION_DATE, new BigDecimal("1000.00"));
        });
        Assertions.assertNull(summary(savingsId).getInterestPostedTillDate(), "the account must not have a pivot");
        return savingsId;
    }

    private void inPivotMode(Runnable test) {
        final GlobalConfigurationPropertyData originalBackdated = globalConfigurationHelper
                .getGlobalConfigurationByName(GlobalConfigurationConstants.ALLOW_BACKDATED_TRANSACTION_BEFORE_INTEREST_POSTING);
        final GlobalConfigurationPropertyData originalRelaxedDays = globalConfigurationHelper.getGlobalConfigurationByName(
                GlobalConfigurationConstants.ALLOW_BACKDATED_TRANSACTION_BEFORE_INTEREST_POSTING_DATE_FOR_DAYS);
        try {
            globalConfigurationHelper.updateGlobalConfiguration(
                    GlobalConfigurationConstants.ALLOW_BACKDATED_TRANSACTION_BEFORE_INTEREST_POSTING,
                    new PutGlobalConfigurationsRequest().enabled(false));
            globalConfigurationHelper.updateGlobalConfiguration(
                    GlobalConfigurationConstants.ALLOW_BACKDATED_TRANSACTION_BEFORE_INTEREST_POSTING_DATE_FOR_DAYS,
                    new PutGlobalConfigurationsRequest().enabled(false));
            Assertions.assertFalse(Boolean.TRUE.equals(globalConfigurationHelper
                    .getGlobalConfigurationByName(GlobalConfigurationConstants.ALLOW_BACKDATED_TRANSACTION_BEFORE_INTEREST_POSTING)
                    .getEnabled()), "pivot mode must be on");
            test.run();
        } finally {
            restoreConfiguration(GlobalConfigurationConstants.ALLOW_BACKDATED_TRANSACTION_BEFORE_INTEREST_POSTING, originalBackdated);
            restoreConfiguration(GlobalConfigurationConstants.ALLOW_BACKDATED_TRANSACTION_BEFORE_INTEREST_POSTING_DATE_FOR_DAYS,
                    originalRelaxedDays);
        }
    }

    private Long createFlatCharge(String amount) {
        return new ChargesHelper().createCharges(new ChargeRequest().active(true)
                .name(Utils.uniqueRandomStringGenerator("No_Pivot_Fee_", 6)).currencyCode("USD").amount(Double.valueOf(amount))
                .chargeAppliesTo(2).chargeTimeType(2).chargeCalculationType(1).locale("en").penalty(false)).getResourceId();
    }

    private void payNewCharge(Long savingsId, Long chargeId, String amount) {
        final Long savingsChargeId = addCharge(savingsId, chargeId, amount);
        ok(fineractClient().savingsAccountCharges
                .payOrWaiveSavingsAccountCharge(
                        savingsId, savingsChargeId, new PostSavingsAccountsSavingsAccountIdChargesSavingsAccountChargeIdRequest()
                                .amount(Float.valueOf(amount)).dueDate(TRANSACTION_DATE).dateFormat(DATETIME_PATTERN).locale("en"),
                        "paycharge"));
    }

    private Long addCharge(Long savingsId, Long chargeId, String amount) {
        return ok(fineractClient().savingsAccountCharges.addSavingsAccountCharge(savingsId,
                new PostSavingsAccountsSavingsAccountIdChargesRequest().chargeId(chargeId).amount(Float.valueOf(amount))
                        .dueDate(TRANSACTION_DATE).dateFormat(DATETIME_PATTERN).locale("en")))
                .getResourceId();
    }

    private Long withdraw(Long savingsId, String amount) {
        return ok(
                fineractClient().savingsTransactions.createSavingsAccountTransaction(savingsId,
                        new PostSavingsAccountTransactionsRequest().dateFormat(DATETIME_PATTERN).locale("en").paymentTypeId(1)
                                .transactionAmount(new BigDecimal(amount)).transactionDate(TRANSACTION_DATE),
                        "withdrawal"))
                .getResourceId();
    }

    private void undo(Long savingsId, Long transactionId) {
        new SavingsAccountHelper(requestSpec, responseSpec).undoSavingsAccountTransaction(savingsId.intValue(), transactionId.intValue());
    }

    private Long lastTransactionId(Long savingsId) {
        return getTransactions(savingsId).stream().filter(t -> !Boolean.TRUE.equals(t.getReversed()))
                .max(Comparator.comparingLong(SavingsAccountTransactionData::getId)).orElseThrow().getId();
    }

    private SavingsAccountSummaryData summary(Long savingsId) {
        return ok(fineractClient().savingsAccounts.retrieveSavingsAccount(savingsId, false, null, "summary")).getSummary();
    }

    private void assertBalance(Long savingsId, String balance) {
        assertAmount("account balance", balance, summary(savingsId).getAccountBalance());
    }

    private void assertSummary(Long savingsId, String balance, String deposits, String withdrawals, String feeCharges) {
        final SavingsAccountSummaryData summary = summary(savingsId);
        assertAmount("account balance", balance, summary.getAccountBalance());
        assertAmount("total deposits", deposits, summary.getTotalDeposits());
        assertAmount("total withdrawals", withdrawals, summary.getTotalWithdrawals());
        if (feeCharges != null) {
            assertAmount("total fee charges", feeCharges, summary.getTotalFeeCharge());
        }
    }

    private void assertLastRunningBalance(Long savingsId, String expected) {
        final List<SavingsAccountTransactionData> transactions = getTransactions(savingsId);
        final SavingsAccountTransactionData last = transactions.stream().filter(t -> !Boolean.TRUE.equals(t.getReversed()))
                .max(Comparator.comparingLong(SavingsAccountTransactionData::getId)).orElseThrow();
        assertAmount("running balance of the last transaction", expected, last.getRunningBalance());
    }

    private void assertAmount(String field, String expected, BigDecimal actual) {
        final BigDecimal actualOrZero = actual == null ? BigDecimal.ZERO : actual;
        Assertions.assertEquals(0, new BigDecimal(expected).compareTo(actualOrZero),
                () -> field + ": expected " + expected + " but was " + actualOrZero);
    }

    private PostSavingsProductsRequest zeroInterestProduct() {
        return new PostSavingsProductsRequest().locale("en").name(Utils.uniqueRandomStringGenerator("ZERO_INTEREST_", 6))
                .shortName(Utils.uniqueRandomStringGenerator("", 4)).description("Zero interest wallet without pivot").currencyCode("USD")
                .digitsAfterDecimal(2).inMultiplesOf(0).nominalAnnualInterestRate(0.0)
                .interestCompoundingPeriodType(InterestPeriodType.DAILY).interestPostingPeriodType(InterestPeriodType.MONTHLY)
                .interestCalculationType(InterestCalculationType.DAILY_BALANCE).interestCalculationDaysInYearType(DaysInYearType.DAYS_365)
                .accountingRule(1).withdrawalFeeForTransfers(false).allowOverdraft(false).enforceMinRequiredBalance(false)
                .withHoldTax(false).isDormancyTrackingActive(false);
    }

    private void restoreConfiguration(final String name, final GlobalConfigurationPropertyData configuration) {
        globalConfigurationHelper.updateGlobalConfiguration(name,
                new PutGlobalConfigurationsRequest().enabled(configuration.getEnabled()).value(configuration.getValue()));
    }
}
