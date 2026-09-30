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
import org.apache.fineract.client.models.GlobalConfigurationPropertyData;
import org.apache.fineract.client.models.PostSavingsAccountTransactionsRequest;
import org.apache.fineract.client.models.PostSavingsAccountsSavingsAccountIdChargesRequest;
import org.apache.fineract.client.models.PostSavingsProductsRequest;
import org.apache.fineract.client.models.PutGlobalConfigurationsRequest;
import org.apache.fineract.infrastructure.configuration.api.GlobalConfigurationConstants;
import org.apache.fineract.integrationtests.common.ClientHelper;
import org.apache.fineract.integrationtests.common.Utils;
import org.apache.fineract.integrationtests.savings.base.BaseSavingsIntegrationTest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Withdrawal fees on an account with a zero-interest pivot: the account summary must match the transactions, and the
 * balance reconciliation job (SA_RBAL) must not change it.
 */
public class SavingsZeroInterestPivotWithdrawalFeeIntegrationTest extends BaseSavingsIntegrationTest {

    private static final String ACTIVATION_DATE = "01 September 2026";
    private static final String PIVOT_RUN_DATE = "02 September 2026";
    private static final String WITHDRAWAL_DATE = "03 September 2026";

    @Test
    public void withdrawalFeesKeepSummaryWithZeroInterestPivot() {
        final GlobalConfigurationPropertyData originalBackdated = globalConfigurationHelper
                .getGlobalConfigurationByName(GlobalConfigurationConstants.ALLOW_BACKDATED_TRANSACTION_BEFORE_INTEREST_POSTING);
        final GlobalConfigurationPropertyData originalRelaxedDays = globalConfigurationHelper.getGlobalConfigurationByName(
                GlobalConfigurationConstants.ALLOW_BACKDATED_TRANSACTION_BEFORE_INTEREST_POSTING_DATE_FOR_DAYS);
        final boolean originalSchedulerStatus = schedulerJobHelper.getSchedulerStatus();

        try {
            globalConfigurationHelper.updateGlobalConfiguration(
                    GlobalConfigurationConstants.ALLOW_BACKDATED_TRANSACTION_BEFORE_INTEREST_POSTING,
                    new PutGlobalConfigurationsRequest().enabled(false));
            globalConfigurationHelper.updateGlobalConfiguration(
                    GlobalConfigurationConstants.ALLOW_BACKDATED_TRANSACTION_BEFORE_INTEREST_POSTING_DATE_FOR_DAYS,
                    new PutGlobalConfigurationsRequest().enabled(false));

            // withdrawal fee of 3.00 on every withdrawal
            final Integer withdrawalFeeId = Utils.performServerPost(requestSpec, responseSpec,
                    "/fineract-provider/api/v1/charges?" + Utils.TENANT_IDENTIFIER,
                    "{\"name\": \"" + Utils.uniqueRandomStringGenerator("Withdrawal_Fee_", 6) + "\", \"currencyCode\": \"USD\", "
                            + "\"chargeAppliesTo\": 2, \"chargeTimeType\": 5, \"chargeCalculationType\": 1, \"amount\": 3, "
                            + "\"active\": true, \"penalty\": false, \"locale\": \"en\"}",
                    "resourceId");

            final Long clientId = clientHelper.createClient(ClientHelper.defaultClientCreationRequest()).getClientId();
            final Long productId = createProduct(zeroInterestProduct()).getResourceId();
            final Long savingsId = applySavingsAccount(applySavingsRequest(clientId, productId, ACTIVATION_DATE)).getSavingsId();

            runAt(ACTIVATION_DATE, () -> {
                approveSavingsAccount(savingsId, ACTIVATION_DATE);
                activateSavingsAccount(savingsId, ACTIVATION_DATE);
                ok(fineractClient().savingsAccountCharges.createSavingsAccountCharge(savingsId,
                        new PostSavingsAccountsSavingsAccountIdChargesRequest().chargeId(withdrawalFeeId.longValue()).amount(3.0f)
                                .locale("en")));
                deposit(savingsId, ACTIVATION_DATE, new BigDecimal("1000.00"));
            });
            runAt(PIVOT_RUN_DATE, () -> schedulerJobHelper.executeAndAwaitJobByShortName("SA_ZIPV"));

            runAt(WITHDRAWAL_DATE, () -> {
                deposit(savingsId, WITHDRAWAL_DATE, new BigDecimal("20.00"));
                withdraw(savingsId, "100.00");
                withdraw(savingsId, "50.00");

                assertSummary(savingsId, "864.00", "1020.00", "150.00", "6.00");

                schedulerJobHelper.executeAndAwaitJobByShortName("SA_RBAL");
                assertSummary(savingsId, "864.00", "1020.00", "150.00", "6.00");
            });
        } finally {
            restoreConfiguration(GlobalConfigurationConstants.ALLOW_BACKDATED_TRANSACTION_BEFORE_INTEREST_POSTING, originalBackdated);
            restoreConfiguration(GlobalConfigurationConstants.ALLOW_BACKDATED_TRANSACTION_BEFORE_INTEREST_POSTING_DATE_FOR_DAYS,
                    originalRelaxedDays);
            schedulerJobHelper.updateSchedulerStatus(originalSchedulerStatus);
        }
    }

    private void withdraw(Long savingsId, String amount) {
        ok(fineractClient().savingsTransactions.createSavingsAccountTransaction(savingsId,
                new PostSavingsAccountTransactionsRequest().dateFormat(DATETIME_PATTERN).locale("en").paymentTypeId(1)
                        .transactionAmount(new BigDecimal(amount)).transactionDate(WITHDRAWAL_DATE),
                "withdrawal"));
    }

    private void assertSummary(Long savingsId, String balance, String deposits, String withdrawals, String withdrawalFees) {
        final var summary = ok(fineractClient().savingsAccounts.retrieveSavingsAccount(savingsId, false, null, "summary")).getSummary();
        assertAmount("account balance", balance, summary.getAccountBalance());
        assertAmount("total deposits", deposits, summary.getTotalDeposits());
        assertAmount("total withdrawals", withdrawals, summary.getTotalWithdrawals());
        assertAmount("total withdrawal fees", withdrawalFees, summary.getTotalWithdrawalFees());
    }

    private void assertAmount(String field, String expected, BigDecimal actual) {
        final BigDecimal actualOrZero = actual == null ? BigDecimal.ZERO : actual;
        Assertions.assertEquals(0, new BigDecimal(expected).compareTo(actualOrZero),
                () -> field + ": expected " + expected + " but was " + actualOrZero);
    }

    private PostSavingsProductsRequest zeroInterestProduct() {
        return new PostSavingsProductsRequest().locale("en").name(Utils.uniqueRandomStringGenerator("ZERO_INTEREST_", 6))
                .shortName(Utils.uniqueRandomStringGenerator("", 4)).description("Zero interest wallet with withdrawal fee")
                .currencyCode("USD").digitsAfterDecimal(2).inMultiplesOf(0).nominalAnnualInterestRate(0.0)
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
