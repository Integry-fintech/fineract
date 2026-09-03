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

import static io.restassured.RestAssured.given;

import java.math.BigDecimal;
import org.apache.fineract.client.models.AccountTransferRequest;
import org.apache.fineract.client.models.ChargeRequest;
import org.apache.fineract.client.models.GlobalConfigurationPropertyData;
import org.apache.fineract.client.models.PaymentTypeCreateRequest;
import org.apache.fineract.client.models.PostChargesResponse;
import org.apache.fineract.client.models.PostSavingsAccountTransactionsRequest;
import org.apache.fineract.client.models.PostSavingsAccountsResponse;
import org.apache.fineract.client.models.PostSavingsAccountsSavingsAccountIdChargesRequest;
import org.apache.fineract.client.models.PostSavingsAccountsSavingsAccountIdChargesResponse;
import org.apache.fineract.client.models.PostSavingsProductsRequest;
import org.apache.fineract.client.models.PostSavingsProductsResponse;
import org.apache.fineract.client.models.PutGlobalConfigurationsRequest;
import org.apache.fineract.client.models.SavingsAccountData;
import org.apache.fineract.infrastructure.configuration.api.GlobalConfigurationConstants;
import org.apache.fineract.integrationtests.common.ClientHelper;
import org.apache.fineract.integrationtests.common.PaymentTypeHelper;
import org.apache.fineract.integrationtests.common.Utils;
import org.apache.fineract.integrationtests.common.charges.ChargesHelper;
import org.apache.fineract.integrationtests.common.savings.SavingsAccountHelper;
import org.apache.fineract.integrationtests.savings.base.BaseSavingsIntegrationTest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class AccountTransferWithdrawalFeeTest extends BaseSavingsIntegrationTest {

    private static final String ACCOUNT_TRANSFER_AMOUNT = "15000.0";
    private static final String TRANSFER_DATE = "01 March 2013";

    @Test
    public void testFromSavingsToSavingsAccountTransferWithWithdrawalFee() {
        // Create withdrawal fee charge (standard withdrawal fee without payment type binding)
        final ChargesHelper chargesHelper = new ChargesHelper();
        final PostChargesResponse withdrawalCharge = chargesHelper
                .createCharges(new ChargeRequest().active(true).name(Utils.uniqueRandomStringGenerator("Charge_Savings_", 6))
                        .currencyCode("USD").amount(100.0d).chargeAppliesTo(2).chargeTimeType(5).chargeCalculationType(1).locale("en"));
        Assertions.assertNotNull(withdrawalCharge.getResourceId());

        // Create clients
        final Long fromClientId = clientHelper.createClient(ClientHelper.defaultClientCreationRequest()).getClientId();
        final Long toClientId = clientHelper.createClient(ClientHelper.defaultClientCreationRequest()).getClientId();

        // Create savings product with withdrawalFeeForTransfers enabled
        final PostSavingsProductsResponse savingsProduct = createProduct(savingsProduct());
        final Long productId = savingsProduct.getResourceId();

        // Create FROM savings account and enable withdrawalFeeForTransfers on it
        final PostSavingsAccountsResponse fromSavingsResponse = applySavingsAccount(
                applySavingsRequest(fromClientId, productId, "01 January 2013"));
        final Long fromSavingsId = fromSavingsResponse.getSavingsId();
        enableWithdrawalFeeForTransfers(fromSavingsId);

        approveSavingsAccount(fromSavingsId, "01 January 2013");
        activateSavingsAccount(fromSavingsId, "01 January 2013");
        deposit(fromSavingsId, "01 January 2013", BigDecimal.valueOf(30000));

        // Add withdrawal fee charge to FROM savings account
        final PostSavingsAccountsSavingsAccountIdChargesResponse chargeResponse = ok(fineractClient().savingsAccountCharges
                .createSavingsAccountCharge(fromSavingsId, new PostSavingsAccountsSavingsAccountIdChargesRequest()
                        .chargeId(withdrawalCharge.getResourceId()).amount(100.0f).locale("en")));
        Assertions.assertNotNull(chargeResponse.getResourceId());

        // Create TO savings account
        final PostSavingsAccountsResponse toSavingsResponse = applySavingsAccount(
                applySavingsRequest(toClientId, productId, "01 January 2013"));
        final Long toSavingsId = toSavingsResponse.getSavingsId();

        approveSavingsAccount(toSavingsId, "01 January 2013");
        activateSavingsAccount(toSavingsId, "01 January 2013");

        // Perform transfer - without null-checks in SavingsAccount.payWithdrawalFee(),
        // this would trigger NPE because paymentDetail is null during account transfers
        ok(fineractClient().accountTransfers.createAccountTransfer(new AccountTransferRequest().fromClientId(String.valueOf(fromClientId))
                .fromAccountId(String.valueOf(fromSavingsId)).fromAccountType("2").fromOfficeId("1").toClientId(String.valueOf(toClientId))
                .toAccountId(String.valueOf(toSavingsId)).toAccountType("2").toOfficeId("1").transferDate(TRANSFER_DATE)
                .transferAmount(ACCOUNT_TRANSFER_AMOUNT).transferDescription("Transfer").dateFormat(DATETIME_PATTERN).locale("en_GB")));

        // Verify balances: charge has no payment type binding, so it applies as normal withdrawal fee
        // from = 30000 - 15000 (transfer) - 100 (withdrawal fee) = 14900
        SavingsAccountData fromSavingsAccount = ok(
                fineractClient().savingsAccounts.retrieveSavingsAccount(fromSavingsId, false, null, "summary"));
        Assertions.assertEquals(BigDecimal.valueOf(14900).setScale(0), fromSavingsAccount.getSummary().getAccountBalance().setScale(0),
                "Verifying From Savings Account Balance after Account Transfer with Withdrawal Fee");

        // to = 0 + 15000 (transfer) = 15000
        SavingsAccountData toSavingsAccount = ok(
                fineractClient().savingsAccounts.retrieveSavingsAccount(toSavingsId, false, null, "summary"));
        Assertions.assertEquals(BigDecimal.valueOf(15000).setScale(0), toSavingsAccount.getSummary().getAccountBalance().setScale(0),
                "Verifying To Savings Account Balance after Account Transfer");
    }

    @Test
    public void testFromSavingsToSavingsAccountTransferWithPaymentTypeWithdrawalFee() {
        // Create Payment Type
        final PaymentTypeHelper paymentTypeHelper = new PaymentTypeHelper();
        final var paymentTypeResponse = paymentTypeHelper
                .createPaymentType(new PaymentTypeCreateRequest().name("Bank Transfer " + System.currentTimeMillis())
                        .description("Payment via bank transfer").isCashPayment(false).position(1L));
        final Long paymentTypeId = paymentTypeResponse.getResourceId();

        // Create withdrawal fee charge WITH payment type binding
        final ChargesHelper chargesHelper = new ChargesHelper();
        final PostChargesResponse withdrawalCharge = chargesHelper.createCharges(new ChargeRequest().active(true)
                .name(Utils.uniqueRandomStringGenerator("Charge_Savings_", 6)).currencyCode("USD").amount(100.0d).chargeAppliesTo(2)
                .chargeTimeType(5).chargeCalculationType(1).locale("en").enablePaymentType(true).paymentTypeId(paymentTypeId));
        Assertions.assertNotNull(withdrawalCharge.getResourceId());

        // Create clients
        final Long fromClientId = clientHelper.createClient(ClientHelper.defaultClientCreationRequest()).getClientId();
        final Long toClientId = clientHelper.createClient(ClientHelper.defaultClientCreationRequest()).getClientId();

        // Create savings product
        final PostSavingsProductsResponse savingsProduct = createProduct(savingsProduct());
        final Long productId = savingsProduct.getResourceId();

        // Create FROM savings account with withdrawal fee enabled for transfers
        final PostSavingsAccountsResponse fromSavingsResponse = applySavingsAccount(
                applySavingsRequest(fromClientId, productId, "01 January 2013"));
        final Long fromSavingsId = fromSavingsResponse.getSavingsId();
        enableWithdrawalFeeForTransfers(fromSavingsId);

        approveSavingsAccount(fromSavingsId, "01 January 2013");
        activateSavingsAccount(fromSavingsId, "01 January 2013");
        deposit(fromSavingsId, "01 January 2013", BigDecimal.valueOf(30000));

        // Add payment-type withdrawal fee charge to FROM savings account
        final PostSavingsAccountsSavingsAccountIdChargesResponse chargeResponse = ok(fineractClient().savingsAccountCharges
                .createSavingsAccountCharge(fromSavingsId, new PostSavingsAccountsSavingsAccountIdChargesRequest()
                        .chargeId(withdrawalCharge.getResourceId()).amount(100.0f).locale("en")));
        Assertions.assertNotNull(chargeResponse.getResourceId());

        // Create TO savings account
        final PostSavingsAccountsResponse toSavingsResponse = applySavingsAccount(
                applySavingsRequest(toClientId, productId, "01 January 2013"));
        final Long toSavingsId = toSavingsResponse.getSavingsId();

        approveSavingsAccount(toSavingsId, "01 January 2013");
        activateSavingsAccount(toSavingsId, "01 January 2013");

        // Perform transfer - without null-checks in SavingsAccount.payWithdrawalFee(),
        // this throws NPE because paymentDetail is null and code tries to call
        // paymentDetail.getPaymentType().getName()
        ok(fineractClient().accountTransfers.createAccountTransfer(new AccountTransferRequest().fromClientId(String.valueOf(fromClientId))
                .fromAccountId(String.valueOf(fromSavingsId)).fromAccountType("2").fromOfficeId("1").toClientId(String.valueOf(toClientId))
                .toAccountId(String.valueOf(toSavingsId)).toAccountType("2").toOfficeId("1").transferDate(TRANSFER_DATE)
                .transferAmount(ACCOUNT_TRANSFER_AMOUNT).transferDescription("Transfer").dateFormat(DATETIME_PATTERN).locale("en_GB")));

        // The payment-type fee is not applied because paymentDetail is null during transfer
        // and the null-check skips the payment type matching
        // from = 30000 - 15000 = 15000
        SavingsAccountData fromSavingsAccount = ok(
                fineractClient().savingsAccounts.retrieveSavingsAccount(fromSavingsId, false, null, "summary"));
        Assertions.assertEquals(BigDecimal.valueOf(15000).setScale(0), fromSavingsAccount.getSummary().getAccountBalance().setScale(0),
                "Verifying From Savings Account Balance after Account Transfer with Payment Type Withdrawal Fee");

        // to = 0 + 15000 = 15000
        SavingsAccountData toSavingsAccount = ok(
                fineractClient().savingsAccounts.retrieveSavingsAccount(toSavingsId, false, null, "summary"));
        Assertions.assertEquals(BigDecimal.valueOf(15000).setScale(0), toSavingsAccount.getSummary().getAccountBalance().setScale(0),
                "Verifying To Savings Account Balance after Account Transfer");
    }

    @Test
    public void testZeroInterestPivotTransferAndUndoPreserveChargeAdjustedBalances() {
        final String activationDate = "01 September 2026";
        final String pivotDate = "02 September 2026";
        final String transactionDate = "03 September 2026";
        final GlobalConfigurationPropertyData originalBackdated = globalConfigurationHelper
                .getGlobalConfigurationByName(GlobalConfigurationConstants.ALLOW_BACKDATED_TRANSACTION_BEFORE_INTEREST_POSTING);
        final GlobalConfigurationPropertyData originalRelaxedDays = globalConfigurationHelper.getGlobalConfigurationByName(
                GlobalConfigurationConstants.ALLOW_BACKDATED_TRANSACTION_BEFORE_INTEREST_POSTING_DATE_FOR_DAYS);
        final boolean originalSchedulerStatus = schedulerJobHelper.getSchedulerStatus();
        final Long[] savingsChargeId = new Long[1];

        try {
            globalConfigurationHelper.updateGlobalConfiguration(
                    GlobalConfigurationConstants.ALLOW_BACKDATED_TRANSACTION_BEFORE_INTEREST_POSTING,
                    new PutGlobalConfigurationsRequest().enabled(false));
            globalConfigurationHelper.updateGlobalConfiguration(
                    GlobalConfigurationConstants.ALLOW_BACKDATED_TRANSACTION_BEFORE_INTEREST_POSTING_DATE_FOR_DAYS,
                    new PutGlobalConfigurationsRequest().enabled(false));

            final ChargesHelper chargesHelper = new ChargesHelper();
            final Long withdrawalFeeId = chargesHelper
                    .createCharges(new ChargeRequest().active(true).name(Utils.uniqueRandomStringGenerator("Transfer_Fee_", 6))
                            .currencyCode("USD").amount(3.0d).chargeAppliesTo(2).chargeTimeType(5).chargeCalculationType(1).locale("en"))
                    .getResourceId();
            final Long flatChargeId = chargesHelper
                    .createCharges(
                            new ChargeRequest().active(true).name(Utils.uniqueRandomStringGenerator("Pivot_Charge_", 6)).currencyCode("USD")
                                    .amount(7.0d).chargeAppliesTo(2).chargeTimeType(2).chargeCalculationType(1).locale("en").penalty(false))
                    .getResourceId();
            final Long fromClientId = clientHelper.createClient(ClientHelper.defaultClientCreationRequest()).getClientId();
            final Long toClientId = clientHelper.createClient(ClientHelper.defaultClientCreationRequest()).getClientId();
            final Long productId = createProduct(savingsProduct().nominalAnnualInterestRate(0.0)).getResourceId();
            final Long fromSavingsId = applySavingsAccount(applySavingsRequest(fromClientId, productId, activationDate)).getSavingsId();
            final Long toSavingsId = applySavingsAccount(applySavingsRequest(toClientId, productId, activationDate)).getSavingsId();

            runAt(activationDate, () -> {
                enableWithdrawalFeeForTransfers(fromSavingsId);
                approveSavingsAccount(fromSavingsId, activationDate);
                activateSavingsAccount(fromSavingsId, activationDate);
                approveSavingsAccount(toSavingsId, activationDate);
                activateSavingsAccount(toSavingsId, activationDate);
                deposit(fromSavingsId, activationDate, new BigDecimal("1000.00"));
                ok(fineractClient().savingsAccountCharges.createSavingsAccountCharge(fromSavingsId,
                        new PostSavingsAccountsSavingsAccountIdChargesRequest().chargeId(withdrawalFeeId).amount(3.0f).locale("en")));
                savingsChargeId[0] = ok(fineractClient().savingsAccountCharges.addSavingsAccountCharge(fromSavingsId,
                        new PostSavingsAccountsSavingsAccountIdChargesRequest().chargeId(flatChargeId).amount(7.0f).dueDate(transactionDate)
                                .dateFormat(DATETIME_PATTERN).locale("en")))
                        .getResourceId();
            });

            runAt(pivotDate, () -> schedulerJobHelper.executeAndAwaitJobByShortName("SA_ZIPV"));

            runAt(transactionDate, () -> {
                final SavingsAccountHelper savingsAccountHelper = new SavingsAccountHelper(requestSpec, responseSpec);
                savingsAccountHelper.payCharge(savingsChargeId[0].intValue(), fromSavingsId.intValue(), "7.00", transactionDate);

                final var transfer = ok(fineractClient().accountTransfers.createAccountTransfer(new AccountTransferRequest()
                        .fromClientId(String.valueOf(fromClientId)).fromAccountId(String.valueOf(fromSavingsId)).fromAccountType("2")
                        .fromOfficeId("1").toClientId(String.valueOf(toClientId)).toAccountId(String.valueOf(toSavingsId))
                        .toAccountType("2").toOfficeId("1").transferDate(transactionDate).transferAmount("100.00")
                        .transferDescription("Pivot transfer").dateFormat(DATETIME_PATTERN).locale("en_GB")));

                assertSummary(fromSavingsId, "890.00", "1000.00", "100.00", "3.00", "7.00");
                assertSummary(toSavingsId, "100.00", "100.00", "0.00", "0.00", "0.00");

                Utils.performServerPost(requestSpec, responseSpec, "/fineract-provider/api/v1/accounttransfers/" + transfer.getResourceId()
                        + "?command=undo&" + Utils.TENANT_IDENTIFIER, "{}", "resourceId");

                assertSummary(fromSavingsId, "993.00", "1000.00", "0.00", "0.00", "7.00");
                assertSummary(toSavingsId, "0.00", "0.00", "0.00", "0.00", "0.00");

                deposit(fromSavingsId, transactionDate, new BigDecimal("10.00"));
                ok(fineractClient().savingsTransactions.createSavingsAccountTransaction(
                        fromSavingsId, new PostSavingsAccountTransactionsRequest().dateFormat(DATETIME_PATTERN).locale("en")
                                .paymentTypeId(1).transactionAmount(new BigDecimal("5.00")).transactionDate(transactionDate),
                        "withdrawal"));
                assertSummary(fromSavingsId, "995.00", "1010.00", "5.00", "3.00", "7.00");

                schedulerJobHelper.executeAndAwaitJobByShortName("SA_RBAL");
                assertSummary(fromSavingsId, "995.00", "1010.00", "5.00", "3.00", "7.00");
                assertSummary(toSavingsId, "0.00", "0.00", "0.00", "0.00", "0.00");
            });
        } finally {
            restoreConfiguration(GlobalConfigurationConstants.ALLOW_BACKDATED_TRANSACTION_BEFORE_INTEREST_POSTING, originalBackdated);
            restoreConfiguration(GlobalConfigurationConstants.ALLOW_BACKDATED_TRANSACTION_BEFORE_INTEREST_POSTING_DATE_FOR_DAYS,
                    originalRelaxedDays);
            schedulerJobHelper.updateSchedulerStatus(originalSchedulerStatus);
        }
    }

    private void assertSummary(final Long savingsId, final String balance, final String deposits, final String withdrawals,
            final String withdrawalFees, final String feeCharges) {
        final var summary = ok(fineractClient().savingsAccounts.retrieveSavingsAccount(savingsId, false, null, "summary")).getSummary();
        assertAmount("account balance", balance, summary.getAccountBalance());
        assertAmount("total deposits", deposits, summary.getTotalDeposits());
        assertAmount("total withdrawals", withdrawals, summary.getTotalWithdrawals());
        assertAmount("total withdrawal fees", withdrawalFees, summary.getTotalWithdrawalFees());
        assertAmount("total fee charges", feeCharges, summary.getTotalFeeCharge());
    }

    private void assertAmount(final String field, final String expected, final BigDecimal actual) {
        final BigDecimal actualOrZero = actual == null ? BigDecimal.ZERO : actual;
        Assertions.assertEquals(0, new BigDecimal(expected).compareTo(actualOrZero),
                () -> field + ": expected " + expected + " but was " + actualOrZero);
    }

    private void restoreConfiguration(final String name, final GlobalConfigurationPropertyData configuration) {
        globalConfigurationHelper.updateGlobalConfiguration(name,
                new PutGlobalConfigurationsRequest().enabled(configuration.getEnabled()).value(configuration.getValue()));
    }

    private void enableWithdrawalFeeForTransfers(Long savingsId) {
        given().spec(requestSpec).body("{\"withdrawalFeeForTransfers\": true}").expect().spec(responseSpec).log().ifError().when()
                .put("/fineract-provider/api/v1/savingsaccounts/" + savingsId + "?" + Utils.TENANT_IDENTIFIER);
    }

    private PostSavingsProductsRequest savingsProduct() {
        return new PostSavingsProductsRequest().locale("en").name(Utils.uniqueRandomStringGenerator("SAVINGS_PRODUCT_", 6))
                .shortName(Utils.uniqueRandomStringGenerator("", 4)).description("Savings product for withdrawal fee test")
                .currencyCode("USD").digitsAfterDecimal(2).inMultiplesOf(0).nominalAnnualInterestRate(10.0)
                .interestCompoundingPeriodType(InterestPeriodType.DAILY).interestPostingPeriodType(InterestPeriodType.MONTHLY)
                .interestCalculationType(InterestCalculationType.DAILY_BALANCE).interestCalculationDaysInYearType(DaysInYearType.DAYS_365)
                .accountingRule(1).withdrawalFeeForTransfers(true).allowOverdraft(false).enforceMinRequiredBalance(false).withHoldTax(false)
                .isDormancyTrackingActive(false);
    }
}
